package httpapi

import "testing"

func TestValidateCIPMetadataAllowsExternalNetworkWithoutHostList(t *testing.T) {
	meta := luaAppMetadata{
		ID: "external_demo", Name: "外网示例", Version: 1,
		Permissions: []string{"network_external"},
	}
	if err := validateCIPMetadata(meta); err != nil {
		t.Fatalf("network_external without allowed_hosts should be valid: %v", err)
	}
}

func TestValidateCIPMetadataAllowsPublicWildcard(t *testing.T) {
	meta := luaAppMetadata{
		ID: "external_wildcard", Name: "外网通配", Version: 1,
		Permissions: []string{"network_external"}, AllowedHosts: []string{"*"},
	}
	if err := validateCIPMetadata(meta); err != nil {
		t.Fatalf("wildcard allowed_hosts should be valid: %v", err)
	}
}
