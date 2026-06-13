package service

import "testing"

// TestNormalizeInterbankFromAccount covers FIX 6: a blank fromAccount (foreign
// Person-only sender, e.g. an inbound OTC premium) must collapse to the
// INTERBANK-EXTERNAL sentinel so the audit row stays insertable (from_account_number
// is VARCHAR(255) NOT NULL) instead of being rejected and vanishing from history.
func TestNormalizeInterbankFromAccount(t *testing.T) {
	cases := []struct {
		name string
		in   string
		want string
	}{
		{"empty -> sentinel", "", interbankExternalSender},
		{"whitespace-only -> sentinel", "   ", interbankExternalSender},
		{"real account passes through", "111000000000000123", "111000000000000123"},
		{"trims surrounding space", "  222000000000000999  ", "222000000000000999"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := normalizeInterbankFromAccount(tc.in); got != tc.want {
				t.Fatalf("normalizeInterbankFromAccount(%q) = %q, want %q", tc.in, got, tc.want)
			}
		})
	}
}
