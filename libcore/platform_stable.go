//go:build !core_preview

package libcore

import "github.com/sagernet/sing-box/adapter"

func connectionOwner(uid int32, name string) *adapter.ConnectionOwner {
	return &adapter.ConnectionOwner{UserId: uid, AndroidPackageNames: []string{name}}
}
