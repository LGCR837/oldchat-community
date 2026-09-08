package data

import (
	"context"
	"testing"
)

func TestChannelReplyIsReturnedWithOriginalContent(t *testing.T) {
	db := newChannelStoreTestDB(t)
	ctx := context.Background()
	db.MustExec(`INSERT INTO users(id,uid,username) VALUES('owner','OWNER','Alice'),('publisher','PUB','Bob')`)
	store := NewChannelStore(db)
	if err := store.Create(ctx, &Channel{ID: "CH-1", OwnerID: "owner", Name: "Test", Visibility: "public"}); err != nil {
		t.Fatal(err)
	}
	db.MustExec(`INSERT INTO channel_subscriptions(channel_id,user_id,role,joined_post_seq,last_read_post_seq) VALUES('CH-1','publisher','publisher',0,0)`)
	if _, _, err := store.CreatePost(ctx, &ChannelPost{ID: "P-1", ChannelID: "CH-1", AuthorID: "owner", Body: "original", MsgType: "text"}); err != nil {
		t.Fatal(err)
	}
	if _, _, err := store.CreatePost(ctx, &ChannelPost{ID: "P-2", ChannelID: "CH-1", AuthorID: "publisher", ReplyToPostID: "P-1", Body: "reply", MsgType: "text"}); err != nil {
		t.Fatal(err)
	}
	posts, _, err := store.ListPostsAfter(ctx, "CH-1", 0, 10)
	if err != nil || len(posts) != 2 {
		t.Fatalf("posts=%+v err=%v", posts, err)
	}
	if posts[1].ReplyToPostID != "P-1" || posts[1].ReplyAuthor != "Alice" || posts[1].ReplyBody != "original" {
		t.Fatalf("reply=%+v", posts[1])
	}
}
