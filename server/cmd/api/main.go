package main

import (
	"context"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"metrochat/internal/config"
	"metrochat/internal/data"
	httpapi "metrochat/internal/http"
)

func main() {
	log.SetFlags(0)
	httpapi.StartTUI()
	cfg, err := config.Load()
	if err != nil {
		log.Fatalf("config error: %v", err)
	}
	if cfg.AdminUser == "admin" && cfg.AdminPassword == "admin123456" {
		log.Printf("%s | WARN | using default admin credentials; set ADMIN_USER/ADMIN_PASSWORD", time.Now().Format("15:04:05"))
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	db, err := data.OpenDB(ctx, cfg.DatabaseURL)
	if err != nil {
		log.Fatalf("db error: %v", err)
	}
	defer db.Close()

	if err := os.MkdirAll(cfg.UploadDir, 0o755); err != nil {
		log.Fatalf("upload dir error: %v", err)
	}
	if err := os.MkdirAll(cfg.UpdateDir, 0o755); err != nil {
		log.Fatalf("update dir error: %v", err)
	}

	h := httpapi.New(cfg, db)

	// 事件流归档：只保留最近 14 天（配合 /updates/difference 的 reset 阈值，
	// 长期离线用户差距超阈值直接走全量兜底，不依赖历史事件）。
	ptsStore := data.NewPtsStore(db)
	groupEventStore := data.NewGroupEventStore(db)
	pruneEvents := func() {
		cutoff := time.Now().Add(-14 * 24 * time.Hour)
		if n, err := ptsStore.PruneBefore(context.Background(), cutoff); err == nil && n > 0 {
			log.Printf("%s | INFO | pruned %d account_events", time.Now().Format("15:04:05"), n)
		}
		if n, err := groupEventStore.PruneBefore(context.Background(), cutoff); err == nil && n > 0 {
			log.Printf("%s | INFO | pruned %d group_events", time.Now().Format("15:04:05"), n)
		}
	}
	pruneEvents()
	go func() {
		ticker := time.NewTicker(6 * time.Hour)
		defer ticker.Stop()
		for {
			select {
			case <-ticker.C:
				pruneEvents()
			case <-ctx.Done():
				return
			}
		}
	}()

	srv := &http.Server{
		Addr:              ":" + cfg.Port,
		Handler:           h,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       5 * time.Minute,
		WriteTimeout:      5 * time.Minute,
		IdleTimeout:       60 * time.Second,
		MaxHeaderBytes:    1 << 20,
	}

	go func() {
		log.Printf("%s | INFO | listening on :%s", time.Now().Format("15:04:05"), cfg.Port)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("server error: %v", err)
		}
	}()

	<-ctx.Done()
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_ = srv.Shutdown(shutdownCtx)
	httpapi.StopTUI()
}
