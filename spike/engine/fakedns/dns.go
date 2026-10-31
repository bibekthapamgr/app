package fakedns

import (
	"strings"

	"golang.org/x/net/dns/dnsmessage"

	"proxyflow/spike/engine/adapters"
)

// Outcome describes how a query was answered (for stats and the tests).
type Outcome string

const (
	OutcomeMapped  Outcome = "mapped"  // A query answered with a fake IP
	OutcomeNoData  Outcome = "nodata"  // AAAA / HTTPS / SVCB answered NOERROR, no records
	OutcomeRefused Outcome = "refused" // other types / classes, invalid names
	OutcomeFormErr Outcome = "formerr" // malformed or unsupported message
	OutcomeIgnored Outcome = "ignored" // not a query (e.g. a response); dropped
)

// Responder answers DNS queries from the fake-IP pool.
type Responder struct {
	Pool *Pool
	TTL  uint32 // seconds; kept short so apps re-query after the tunnel stops
}

// Handle parses one DNS query and returns the wire-format response. A nil
// response means "drop" (only for messages that are not queries).
func (r *Responder) Handle(query []byte) ([]byte, Outcome, string) {
	var p dnsmessage.Parser
	hdr, err := p.Start(query)
	if err != nil {
		return nil, OutcomeIgnored, "" // too short to even echo an ID
	}
	if hdr.Response {
		return nil, OutcomeIgnored, ""
	}
	questions, err := p.AllQuestions()
	if err != nil || len(questions) != 1 || hdr.OpCode != 0 {
		return r.reply(hdr, nil, dnsmessage.RCodeFormatError, nil), OutcomeFormErr, ""
	}
	q := questions[0]
	if q.Class != dnsmessage.ClassINET {
		return r.reply(hdr, &q, dnsmessage.RCodeRefused, nil), OutcomeRefused, ""
	}
	name, ok := normaliseName(q.Name)
	if !ok {
		// Includes names with spaces / CR / LF / ':' that could inject into CONNECT.
		return r.reply(hdr, &q, dnsmessage.RCodeRefused, nil), OutcomeRefused, ""
	}
	switch q.Type {
	case dnsmessage.TypeA:
		ip := r.Pool.Lookup(name)
		ans := &dnsmessage.AResource{A: ip.As4()}
		return r.reply(hdr, &q, dnsmessage.RCodeSuccess, ans), OutcomeMapped, name
	case dnsmessage.TypeAAAA, dnsmessage.TypeHTTPS, dnsmessage.TypeSVCB:
		// V1 maps IPv4 only; the proxy chooses the address family for the
		// hostname, so apps fall back to the A record (ADR-0003).
		return r.reply(hdr, &q, dnsmessage.RCodeSuccess, nil), OutcomeNoData, name
	}
	return r.reply(hdr, &q, dnsmessage.RCodeRefused, nil), OutcomeRefused, name
}

func (r *Responder) reply(qh dnsmessage.Header, q *dnsmessage.Question, rcode dnsmessage.RCode, a *dnsmessage.AResource) []byte {
	b := dnsmessage.NewBuilder(make([]byte, 0, 512), dnsmessage.Header{
		ID: qh.ID, Response: true, OpCode: qh.OpCode, RecursionDesired: qh.RecursionDesired,
		RecursionAvailable: true, RCode: rcode,
	})
	b.EnableCompression()
	if err := b.StartQuestions(); err != nil {
		return nil
	}
	if q != nil {
		if err := b.Question(*q); err != nil {
			return nil
		}
	}
	if a != nil {
		if err := b.StartAnswers(); err != nil {
			return nil
		}
		if err := b.AResource(dnsmessage.ResourceHeader{Name: q.Name, Class: dnsmessage.ClassINET, TTL: r.TTL}, *a); err != nil {
			return nil
		}
	}
	out, err := b.Finish()
	if err != nil {
		return nil
	}
	return out
}

// normaliseName lower-cases, strips the trailing dot and validates the name.
func normaliseName(n dnsmessage.Name) (string, bool) {
	s := strings.TrimSuffix(strings.ToLower(n.String()), ".")
	if !adapters.ValidHostname(s) {
		return "", false
	}
	return s, true
}
