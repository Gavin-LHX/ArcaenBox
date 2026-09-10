"""Select exactly one native Go runtime before gomobile initializes its bridge."""
from pathlib import Path
import sys
p=Path(sys.argv[1])
s=p.read_text()
old='System.loadLibrary("gojni");'
new='''try {
            Class.forName("io.nekohasekai.sagernet.update.CoreRuntime").getMethod("load").invoke(null);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }'''
if old in s:
    p.write_text(s.replace(old,new))
elif new not in s:
    raise SystemExit('Unexpected gomobile loader')
