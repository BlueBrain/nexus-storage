use std::ffi::CString;
use std::os::unix::ffi::OsStrExt;
use std::path::Path;

use libc::{chmod, chown};

use crate::config::*;
use crate::errors::Failure;
use crate::errors::Failure::*;

pub fn check_path(path: &str) -> Result<&Path, Failure> {
    let p = Path::new(path);
    if p.is_relative() {
        Err(PathMustBeAbsolute)
    } else if !p.starts_with(get_path_prefix()) {
        Err(PathMustStartWithPrefix)
    } else if !p.exists() {
        Err(FileNotFound)
    } else if p.canonicalize()? != p.to_path_buf() {
        Err(PathMustBeCanonical)
    } else {
        Ok(p)
    }
}

pub fn visit_all(path: &Path) -> Result<(), Failure> {
    if path.is_dir() {
        for entry in path.read_dir()? {
            let entry = entry?;
            visit_all(&entry.path())?;
        }
        set_owner(path).and_then(|_| set_permissions(path, DIR_MASK))
    } else {
        set_owner(path).and_then(|_| set_permissions(path, FILE_MASK))
    }
}

pub fn set_owner(path: &Path) -> Result<(), Failure> {
    let p = CString::new(path.as_os_str().as_bytes()).map_err(|_| PathCannotHaveNul)?;
    let uid = get_uid()?;
    let gid = get_gid()?;
    let chown = unsafe { chown(p.as_ptr() as *const i8, uid, gid) };
    if chown == 0 {
        Ok(())
    } else {
        Err(ChownFailed)
    }
}

pub fn set_permissions(path: &Path, mask: u32) -> Result<(), Failure> {
    let p = CString::new(path.as_os_str().as_bytes()).map_err(|_| PathCannotHaveNul)?;
    let chmod = unsafe { chmod(p.as_ptr() as *const i8, mask) };
    if chmod == 0 {
        Ok(())
    } else {
        Err(ChmodFailed)
    }
}

#[cfg(test)]
mod tests {
    use std::fs;
    use std::io;
    use std::os::unix::fs::{MetadataExt, PermissionsExt};

    use rand::{thread_rng, Rng};

    use super::*;

    #[test]
    fn test_check_path() {
        assert_eq!(check_path("../foo"), Err(PathMustBeAbsolute));
        assert_eq!(check_path("/foo"), Err(PathMustStartWithPrefix));
        assert_eq!(check_path("/tmp/nexus-fixer/bar"), Err(FileNotFound));
        assert!(touch(Path::new("/tmp/nexus-fixer/bar")).is_ok());
        assert!(check_path("/tmp/nexus-fixer/bar").is_ok());
        assert_eq!(
            check_path("/tmp/nexus-fixer/../nexus-fixer/bar"),
            Err(PathMustBeCanonical)
        );
    }

    #[test]
    fn test_set_owner() {
        let p = Path::new("/tmp/nexus-fixer/baz");
        assert!(touch(p).is_ok());
        assert!(set_owner(p).is_ok());
        check_owner(
            p,
            get_uid().expect("failed to read UID"),
            get_gid().expect("failed to read GID"),
        );
    }

    #[test]
    fn test_set_permissions() {
        let p = Path::new("/tmp/nexus-fixer/qux");
        let mask = random_mask();
        assert!(touch(p).is_ok());
        assert!(set_permissions(p, mask).is_ok());
        check_permissions(p, mask);
    }

    #[test]
    fn test_visit_all() {
        let p = Path::new("/tmp/nexus-fixer/a/b/c");
        assert!(fs::create_dir_all(p).is_ok());
        let file_a = Path::new("/tmp/nexus-fixer/a/file_a");
        let file_b = Path::new("/tmp/nexus-fixer/a/b/file_b");
        let file_c = Path::new("/tmp/nexus-fixer/a/b/c/file_c");
        assert!(touch(file_a).is_ok());
        assert!(touch(file_b).is_ok());
        assert!(touch(file_c).is_ok());
        assert!(visit_all(Path::new("/tmp/nexus-fixer/a")).is_ok());

        let uid = get_uid().expect("failed to read UID");
        let gid = get_gid().expect("failed to read GID");

        // dirs
        check_permissions(Path::new("/tmp/nexus-fixer/a"), DIR_MASK);
        check_owner(Path::new("/tmp/nexus-fixer/a"), uid, gid);
        check_permissions(Path::new("/tmp/nexus-fixer/a/b"), DIR_MASK);
        check_owner(Path::new("/tmp/nexus-fixer/a/b"), uid, gid);
        check_permissions(Path::new("/tmp/nexus-fixer/a/b/c"), DIR_MASK);
        check_owner(Path::new("/tmp/nexus-fixer/a/b/c"), uid, gid);
        // files
        check_permissions(file_a, FILE_MASK);
        check_owner(file_a, uid, gid);
        check_permissions(file_b, FILE_MASK);
        check_owner(file_b, uid, gid);
        check_permissions(file_c, FILE_MASK);
        check_owner(file_c, uid, gid);
    }

    fn check_owner(path: &Path, uid: u32, gid: u32) {
        let metadata = fs::metadata(path).expect("failed to read file metadata");
        assert_eq!(metadata.uid(), uid);
        assert_eq!(metadata.gid(), gid);
    }

    fn check_permissions(path: &Path, mask: u32) {
        let metadata = fs::metadata(path).expect("failed to read file metadata");
        assert_eq!(metadata.permissions().mode() & 0o777, mask);
    }

    fn random_mask() -> u32 {
        thread_rng().gen_range(0, 0o1000)
    }

    // A simple implementation of `% touch path` (ignores existing files)
    fn touch(path: &Path) -> io::Result<()> {
        match fs::OpenOptions::new().create(true).write(true).open(path) {
            Ok(_) => Ok(()),
            Err(e) => Err(e),
        }
    }
}
