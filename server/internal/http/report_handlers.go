package httpapi

import (
	"context"
	"log"
	"net/http"
	"strings"
	"time"

	"metrochat/internal/data"
)

func (a *API) handleUserReport(w http.ResponseWriter, r *http.Request) {
	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}
	var body struct {
		TargetUID      string `json:"target_uid"`
		TargetNcuid    string `json:"target_ncuid"`
		Reason         string `json:"reason"`
		Evidence       string `json:"evidence"`
		ReportEvidence string `json:"report_evidence"`
		TargetChannel  string `json:"target_channel"`
		ToPublicCourt  *bool  `json:"to_public_court"`
	}
	if !requireJSON(w, r) {
		return
	}
	if err := decodeJSON(w, r, &body); err != nil {
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	reporter, err := a.users.GetByID(ctx, claims.Subject)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}
	targetUser, err := a.resolveUserIdentity(ctx, body.TargetUID, body.TargetNcuid)
	if err != nil {
		writeUserIdentityError(w, err)
		return
	}
	if reporter.ID == targetUser.ID || (reporter.UID != "" && strings.EqualFold(reporter.UID, targetUser.UID)) {
		writeError(w, http.StatusBadRequest, "cannot_report_self", "不能举报自己")
		return
	}
	deviceID, _, _ := a.devices.LatestDeviceForUser(ctx, targetUser.ID)
	reason := strings.TrimSpace(body.Reason)
	evidence := strings.TrimSpace(body.Evidence)
	if evidence == "" {
		evidence = strings.TrimSpace(body.ReportEvidence)
	}
	if evidence == "" {
		evidence = reason
	}
	if reason == "" {
		reason = "违规行为举报"
	}

	report := &data.UserReport{
		ReporterID:   reporter.ID,
		ReporterUID:  reporter.UID,
		TargetUserID: targetUser.ID,
		TargetUID:    targetUser.UID,
		TargetDevice: deviceID,
		Reason:       reason + "\n\n【证据与证词记录】\n" + evidence,
	}
	if err := a.reportStore.CreateUserReport(ctx, report); err != nil {
		writeError(w, http.StatusInternalServerError, "internal_error", "failed to save report")
		return
	}

	toCourt := true
	if strings.EqualFold(strings.TrimSpace(body.TargetChannel), "audit") ||
		strings.EqualFold(strings.TrimSpace(body.TargetChannel), "audit_only") ||
		(body.ToPublicCourt != nil && !*body.ToPublicCourt) {
		toCourt = false
	}

	caseID := ""
	if toCourt && a.publicCourt != nil {
		courtCase, _, err := a.publicCourt.OpenOrCreate(ctx, &data.PublicCourtCase{
			ReportID:       report.ID,
			ReporterID:     reporter.ID,
			ReporterUID:    reporter.UID,
			DefendantID:    targetUser.ID,
			DefendantUID:   targetUser.UID,
			ReportReason:   reason,
			ReportEvidence: evidence,
			Status:         "open",
		})
		if err != nil {
			log.Printf("public court open or create failed: %v", err)
		}
		if courtCase != nil {
			caseID = courtCase.ID
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"message": "report submitted",
		"case_id": caseID,
	})
}

func (a *API) handleGroupReport(w http.ResponseWriter, r *http.Request) {
	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}
	var body struct {
		GroupID string `json:"group_id"`
		Reason  string `json:"reason"`
	}
	if !requireJSON(w, r) {
		return
	}
	if err := decodeJSON(w, r, &body); err != nil {
		return
	}
	groupID := strings.ToUpper(strings.TrimSpace(body.GroupID))
	if !isValidGroupID(groupID) {
		writeError(w, http.StatusBadRequest, "invalid_group_id", "group_id is required")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	reporter, err := a.users.GetByID(ctx, claims.Subject)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}
	group, err := a.groups.GetByID(ctx, groupID)
	if err != nil || group == nil {
		if err == data.ErrNotFound {
			writeError(w, http.StatusNotFound, "not_found", "group not found")
			return
		}
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}
	report := &data.GroupReport{
		ReporterID:  reporter.ID,
		ReporterUID: reporter.UID,
		GroupID:     group.ID,
		GroupName:   group.Name,
		Reason:      strings.TrimSpace(body.Reason),
	}
	if err := a.groupReportStore.Create(ctx, report); err != nil {
		writeError(w, http.StatusInternalServerError, "internal_error", "failed to save report")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"message": "report submitted",
	})
}
