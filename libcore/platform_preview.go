//go:build core_preview

package libcore

import (
	"github.com/sagernet/sing-box/adapter"
	"os"
)

func connectionOwner(uid int32, name string) *adapter.ConnectionOwner {
	return &adapter.ConnectionOwner{UserId: uid, PackageNames: []string{name}}
}
func (s *platformStub) UsePlatformAutoRedirect() bool { return false }
func (s *platformStub) CreateAutoRedirect(options adapter.AutoRedirectOptions) (adapter.AutoRedirectSession, error) {
	return nil, os.ErrInvalid
}
