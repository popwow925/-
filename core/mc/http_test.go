package mc

import (
	"bytes"
	"crypto/sha1"
	"encoding/hex"
	"math/rand"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sync/atomic"
	"testing"
	"time"
)

func blob(n int) ([]byte, string) {
	b := make([]byte, n)
	rand.New(rand.NewSource(int64(n))).Read(b)
	h := sha1.Sum(b)
	return b, hex.EncodeToString(h[:])
}

func TestStallFallsBackToAlt(t *testing.T) {
	StallTimeout = 400 * time.Millisecond
	defer func() { StallTimeout = 8 * time.Second }()
	data, sum := blob(300 << 10)
	stall := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Length", "307200")
		w.Write(data[:1000])
		w.(http.Flusher).Flush()
		time.Sleep(5 * time.Second) // never finishes
	}))
	defer stall.Close()
	good := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write(data) }))
	defer good.Close()
	p := filepath.Join(t.TempDir(), "a.bin")
	t0 := time.Now()
	err := Batch(t.Context(), "t", []File{{URL: stall.URL + "/a", Alt: []string{good.URL + "/a"}, Path: p, SHA1: sum, Size: int64(len(data))}}, 4, nil)
	if err != nil {
		t.Fatal(err)
	}
	if d := time.Since(t0); d > 3*time.Second {
		t.Fatalf("took %v", d)
	}
	if b, _ := os.ReadFile(p); !bytes.Equal(b, data) {
		t.Fatal("content")
	}
}

func TestRangedDownload(t *testing.T) {
	RangeThreshold = 64 << 10
	defer func() { RangeThreshold = 6 << 20 }()
	data, sum := blob(1<<20 + 123)
	var ranged int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Range") != "" {
			atomic.AddInt32(&ranged, 1)
		}
		http.ServeContent(w, r, "x", time.Now(), bytes.NewReader(data))
	}))
	defer srv.Close()
	noRange := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write(data) }))
	defer noRange.Close()
	dir := t.TempDir()
	var got int64
	rep := func(p Progress) { got = p.Bytes }
	err := Batch(t.Context(), "t", []File{
		{URL: srv.URL + "/big", Path: filepath.Join(dir, "a"), SHA1: sum, Size: int64(len(data))},
		{URL: noRange.URL + "/big", Path: filepath.Join(dir, "b"), SHA1: sum, Size: int64(len(data))},
	}, 4, rep)
	if err != nil {
		t.Fatal(err)
	}
	if ranged < int32(RangeParts) {
		t.Fatalf("ranged requests %d", ranged)
	}
	for _, n := range []string{"a", "b"} {
		if b, _ := os.ReadFile(filepath.Join(dir, n)); !bytes.Equal(b, data) {
			t.Fatal("content", n)
		}
	}
	if got != int64(2*len(data)) {
		t.Fatalf("progress bytes %d", got)
	}
}

func TestBadSourceSkippedFast(t *testing.T) {
	data, sum := blob(2048)
	var hits int32
	bad := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&hits, 1)
		w.WriteHeader(429)
	}))
	defer bad.Close()
	good := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write(data) }))
	defer good.Close()
	var files []File
	dir := t.TempDir()
	for i := 0; i < 40; i++ {
		files = append(files, File{URL: bad.URL + "/x", Alt: []string{good.URL + "/x"}, Path: filepath.Join(dir, string(rune('a'+i%26))+string(rune('a'+i/26))), SHA1: sum, Size: 2048})
	}
	t0 := time.Now()
	if err := Batch(t.Context(), "t", files, 16, nil); err != nil {
		t.Fatal(err)
	}
	if time.Since(t0) > 2*time.Second {
		t.Fatal("slow fallback", time.Since(t0))
	}
}

func TestCandidatesAuto(t *testing.T) {
	u := "https://piston-data.mojang.com/v1/objects/x/client.jar"
	autoPick.Store("official")
	if c := Candidates(u); c[0] != u {
		t.Fatal(c)
	}
	autoPick.Store("bmclapi")
	if c := Candidates(u); c[0] == u {
		t.Fatal(c)
	}
	autoPick.Store("")
}

func TestHedgedSlowPrimary(t *testing.T) {
	data, sum := blob(900 << 10)
	slow := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Length", "921600")
		for i := 0; i < 60; i++ { // ~10 KB/s
			if _, err := w.Write(data[i*1024 : (i+1)*1024]); err != nil {
				return
			}
			w.(http.Flusher).Flush()
			select {
			case <-r.Context().Done():
				return
			case <-time.After(100 * time.Millisecond):
			}
		}
	}))
	defer slow.Close()
	fast := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write(data) }))
	defer fast.Close()
	p := filepath.Join(t.TempDir(), "mod.jar")
	t0 := time.Now()
	var got int64
	err := Batch(t.Context(), "t", []File{{URL: slow.URL + "/m", Alt: []string{fast.URL + "/m"}, Path: p, SHA1: sum, Size: int64(len(data))}}, 4,
		func(pr Progress) { got = pr.Bytes })
	if err != nil {
		t.Fatal(err)
	}
	if d := time.Since(t0); d > 3500*time.Millisecond {
		t.Fatalf("hedge too slow: %v", d)
	}
	if b, _ := os.ReadFile(p); !bytes.Equal(b, data) {
		t.Fatal("content")
	}
	if got != int64(len(data)) {
		t.Fatalf("progress bytes %d (loser's bytes must be subtracted)", got)
	}
	if hostSpeed(fast.URL) == 0 {
		t.Fatal("speed not learned")
	}
}
