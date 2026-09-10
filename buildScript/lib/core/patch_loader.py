"""Select exactly one native Go runtime before gomobile initializes its bridge."""
from pathlib import Path
import sys
if sys.argv[1] == '--verify':
    import io, zipfile
    with zipfile.ZipFile(sys.argv[2]) as aar:
        with zipfile.ZipFile(io.BytesIO(aar.read('classes.jar'))) as jar:
            loader=jar.read('go/Seq.class')
            assert b'io.nekohasekai.sagernet.update.CoreRuntime' in loader, 'CoreRuntime loader missing from compiled bridge'
            assert b'loadLibrary' not in loader, 'Compiled bridge still hardcodes libgojni'
    with zipfile.ZipFile(sys.argv[3]) as sources:
        loader=sources.read('go/Seq.java')
        assert b'CoreRuntime' in loader and b'System.loadLibrary' not in loader
    print('Verified selectable core loader in compiled bridge and source archive')
    sys.exit(0)
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
