package data

import (
	"context"
	"testing"
)

func TestChannelReactionToggleAndEventSequence(t *testing.T) {
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
	if _, _, err := store.CreatePost(ctx, &ChannelPost{ID: "P-1", ChannelID: "CH-1", AuthorID: "owner", Body: "hello", MsgType: "text"}); err != nil {
		t.Fatal(err)
	}
	counts, eventSeq, err := store.ToggleReaction(ctx, "CH-1", "P-1", "reader", "👍")
	if err != nil || eventSeq != 2 || len(counts) != 1 || counts[0].Count != 1 || !counts[0].Mine {
		t.Fatalf("add counts=%+v event=%d err=%v", counts, eventSeq, err)
	}
	counts, eventSeq, err = store.ToggleReaction(ctx, "CH-1", "P-1", "reader", "👍")
	if err != nil || eventSeq != 3 || len(counts) != 0 {
		t.Fatalf("remove counts=%+v event=%d err=%v", counts, eventSeq, err)
	}
	var accountEvents int
	db.Get(&accountEvents, `SELECT COUNT(*) FROM account_events`)
	if accountEvents != 0 {
		t.Fatalf("reaction wrote %d account events", accountEvents)
	}
}
