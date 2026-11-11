package data

import (
	"context"
	"testing"
)

func TestChannelEditDeleteEventsAndUnreadCorrection(t *testing.T) {
	db := newChannelStoreTestDB(t)
	ctx := context.Background()
	db.MustExec(`INSERT INTO users(id,uid) VALUES('owner','OWNER'),('reader','READER')`)
	store := NewChannelStore(db)
	if err := store.Create(ctx, &Channel{ID: "CH-1", OwnerID: "owner", Name: "Test", Visibility: "public"}); err != nil {
		t.Fatal(err)
	}
	if _, err := store.Subscribe(ctx, "CH-1", "reader"); err != nil {
		t.Fatal(err)
	}
	if _, _, err := store.CreatePost(ctx, &ChannelPost{ID: "P-1", ChannelID: "CH-1", AuthorID: "owner", Body: "old", MsgType: "text"}); err != nil {
		t.Fatal(err)
	}
	if eventSeq, err := store.EditPost(ctx, "CH-1", "P-1", "new"); err != nil || eventSeq != 2 {
		t.Fatalf("edit event=%d err=%v", eventSeq, err)
	}
	events, serverEventSeq, err := store.ListEventsAfter(ctx, "CH-1", 0, 10)
	if err != nil || len(events) != 2 || serverEventSeq != 2 || events[1].EventType != "POST_EDIT" {
		t.Fatalf("events=%+v server=%d err=%v", events, serverEventSeq, err)
	}
	if eventSeq, err := store.DeletePost(ctx, "CH-1", "P-1"); err != nil || eventSeq != 3 {
		t.Fatalf("delete event=%d err=%v", eventSeq, err)
	}
	state, err := store.State(ctx, "CH-1", "reader")
	if err != nil || state.UnreadCount != 0 || state.PostSeq != 1 || state.EventSeq != 3 {
		t.Fatalf("state=%+v err=%v", state, err)
	}
}
