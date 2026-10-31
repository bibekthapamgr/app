package adapters

import (
	"bytes"
	"encoding/json"
	"fmt"
	"strings"
	"testing"
)

// creds builds test credentials.
func creds(user, pass string) *Credentials { return NewCredentials([]byte(user), []byte(pass)) }

// encodeCredentials builds the app's CredentialCodec format (version 1).
func encodeCredentials(user, pass string) []byte {
	b := []byte{1, byte(len(user) >> 8), byte(len(user))}
	b = append(b, user...)
	b = append(b, byte(len(pass)>>8), byte(len(pass)))
	return append(b, pass...)
}

func TestParseCredentials(t *testing.T) {
	c, err := ParseCredentials(encodeCredentials("alice", "pä55wörd"))
	if err != nil {
		t.Fatal(err)
	}
	if !c.Present() {
		t.Fatal("sign-in must be on")
	}
	_ = c.use(func(user, pass []byte) error {
		if string(user) != "alice" || string(pass) != "pä55wörd" {
			t.Fatalf("decoded %q/%q", user, pass)
		}
		return nil
	})

	empty, err := ParseCredentials(nil)
	if err != nil || empty.Present() {
		t.Fatalf("empty input means no sign-in, got %v %v", empty.Present(), err)
	}

	bad := map[string][]byte{
		"unknown version":   append([]byte{2}, encodeCredentials("a", "b")[1:]...),
		"truncated length":  {1, 0},
		"truncated user":    {1, 0, 5, 'a'},
		"missing password":  {1, 0, 1, 'a'},
		"truncated pass":    {1, 0, 1, 'a', 0, 4, 'x'},
		"trailing bytes":    append(encodeCredentials("a", "b"), 0),
		"invalid UTF-8":     encodeCredentials("a\xff", "b"),
		"invalid UTF-8 pwd": encodeCredentials("a", "\xc3"),
	}
	for name, in := range bad {
		if _, err := ParseCredentials(in); err == nil {
			t.Errorf("%s: expected an error", name)
		} else if strings.Contains(err.Error(), "\xff") {
			t.Errorf("%s: error echoes input bytes: %q", name, err)
		}
	}
}

func TestParseCredentialsCopiesInput(t *testing.T) {
	in := encodeCredentials("user", "secret")
	c, err := ParseCredentials(in)
	if err != nil {
		t.Fatal(err)
	}
	wipe(in) // the caller (Kotlin) zeroes its copy right after Start
	_ = c.use(func(user, pass []byte) error {
		if string(user) != "user" || string(pass) != "secret" {
			t.Fatalf("credentials must not alias the caller's buffer, got %q/%q", user, pass)
		}
		return nil
	})
}

func TestWipeZeroesSecrets(t *testing.T) {
	c := NewCredentials([]byte("user"), []byte("secret"))
	var u, p []byte
	_ = c.use(func(user, pass []byte) error { u, p = user, pass; return nil })
	c.Wipe()
	if !bytes.Equal(u, make([]byte, 4)) || !bytes.Equal(p, make([]byte, 6)) {
		t.Fatalf("Wipe must zero the stored bytes, got %q %q", u, p)
	}
	if c.Present() {
		t.Fatal("wiped credentials must report no sign-in")
	}
	if err := c.use(func(_, _ []byte) error { return nil }); err != errWiped {
		t.Fatalf("use after Wipe = %v, want errWiped", err)
	}
	c.Wipe() // idempotent
	var nilCreds *Credentials
	nilCreds.Wipe()
	if nilCreds.Present() {
		t.Fatal("nil credentials mean no sign-in")
	}
}

func TestCredentialsNeverFormatted(t *testing.T) {
	c := NewCredentials([]byte("alice-user"), []byte("hunter2-secret"))
	type holder struct {
		Creds *Credentials
		Name  string
	}
	h := holder{Creds: c, Name: "x"}
	js, err := json.Marshal(h)
	if err != nil {
		t.Fatal(err)
	}
	outputs := []string{
		fmt.Sprintf("%v", c), fmt.Sprintf("%+v", c), fmt.Sprintf("%#v", c), fmt.Sprintf("%s", c),
		fmt.Sprintf("%q", c), fmt.Sprintf("%x", c), fmt.Sprintf("%X", c), fmt.Sprintf("%d", c),
		fmt.Sprint(c), fmt.Sprintf("%v", h), fmt.Sprintf("%+v", h), fmt.Sprintf("%#v", h),
		c.String(), c.GoString(), string(js),
	}
	for _, s := range outputs {
		for _, secret := range []string{"alice", "hunter2", "616c696365", "68756e74657232"} {
			if strings.Contains(strings.ToLower(s), secret) {
				t.Fatalf("credentials leaked into %q", s)
			}
		}
	}
}

func FuzzParseCredentials(f *testing.F) {
	f.Add(encodeCredentials("user", "pass"))
	f.Add([]byte{})
	f.Add([]byte{1, 0, 0, 0, 0})
	f.Add([]byte{1, 0xff, 0xff})
	f.Fuzz(func(t *testing.T, data []byte) {
		c, err := ParseCredentials(data)
		if err != nil {
			return
		}
		// Whatever parsed must re-encode to exactly the input (no silent truncation).
		var again []byte
		_ = c.use(func(user, pass []byte) error {
			if len(data) == 0 {
				return nil
			}
			again = encodeCredentials(string(user), string(pass))
			return nil
		})
		if len(data) > 0 && string(again) != string(data) {
			t.Fatalf("round trip changed the credentials")
		}
		c.Wipe()
	})
}

func FuzzConnectRequest(f *testing.F) {
	f.Add("user", "pass", "example.com", uint16(443))
	f.Add("", "", "a.b", uint16(1))
	f.Fuzz(func(t *testing.T, user, pass, host string, port uint16) {
		if port == 0 || !ValidHostname(host) {
			return
		}
		c := creds(user, pass)
		req, err := connectRequest(Target{Host: host, Port: port}.HostPort(), c, c.Present())
		if err != nil {
			return
		}
		// One request line, one Host header, at most one Proxy-Authorization, then the blank line:
		// no credential byte can break the header block (it is Base64).
		lines := bytes.Split(req, []byte("\r\n"))
		if n := len(lines); n < 4 || n > 5 || len(lines[n-1]) != 0 || len(lines[n-2]) != 0 {
			t.Fatalf("malformed request %q", req)
		}
	})
}
