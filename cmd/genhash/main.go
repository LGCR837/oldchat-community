package main

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"os"

	"metrochat/internal/auth"
)

func main() {
	if len(os.Args) < 2 {
		fmt.Println("usage: genhash <password>")
		os.Exit(1)
	}
	hash, err := auth.HashPassword(os.Args[1])
	if err != nil {
		panic(err)
	}
	buf := make([]byte, 10)
	if _, err := rand.Read(buf); err != nil {
		panic(err)
	}
	fmt.Println("HASH=" + hash)
	fmt.Println("NCUID=nc_" + hex.EncodeToString(buf))
}
