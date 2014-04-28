Change Log
==========

### Release 0.1.2-alpha (Date: tbd.)

- Developer/alpha release (**NOT FOR PRODUCTION USE!**)
- Features:
  + Extracted non-core plugins, allow easy plugin installation through
    `sy plugin (list|install|remove)` #26/#104
    - Shipped plugins now only 'local'
    - Installable plugins:
      [FTP](https://github.com/syncany/syncany-plugin-ftp),
      [SFTP](https://github.com/syncany/syncany-plugin-sftp) (no host checking),
      [WebDAV](https://github.com/syncany/syncany-plugin-webdav) (HTTP only),
      [Amazon S3](https://github.com/syncany/syncany-plugin-s3)
  + Ignore files using wildcards in .syignore (e.g. *.bak, *.r??) #108
  + Added Arch Linux 'syncany-git' package #99
  + Allow speicifying HTTP(S)/WebDAV proxy and other global system properties #109
- Bugfixes
  + Fix semantic in TransferManager `test()` (incl. all plugins) #103/#102
  + WebDAV plugin fix to create "multichunks"/"databases" folder #110
  + Fix "Plugin not supported" stack trace #111
  + Windows build script fix for "Could not normalize path" #107
  + Fix database file name leak of username and hostname #114
  + Check plugin compatibility before installing (check appMinVersion) #104
  + Don't ignore local/remote notifications if sync already running #88
  + Uninstall plugins on Windows (JAR locked) #113/#117
  + Rotate logs to max. 4x25 MB #116
  + Fix multichunk resource close issue #118/#120
  
### Release 0.1.1-alpha (Date: 14 Apr 2014)

- Developer/alpha release (**NOT FOR PRODUCTION USE!**)
- Features:
  + Ignoring files using .syignore file #66/#77
  + Arch Linux package support; release version #80 and git version #99
  + Additional command-specific --help texts
- Windows-specific: 
  + Add Syncany binaries to PATH environment variable during setup #84/#91
  + Fixed HSQLDB-path issue #98
- Bugfixes:
  + Timezone fix in tests #78/#90
  + Reference issue "Cannot determine file content for checksum" #92/#94
  + Atomic 'init' command (rollback on failure) #95/#96
- Other things:
  + Tests for 'connect' command  
  + Tests for .syignore

### Release 0.1.0-alpha (Date: 30 March 2014)

- First developer/alpha release (**NOT FOR PRODUCTION USE!**)
- Command line interface (CLI) with commands
  + init: initialize local folder and remote repository
  + connect
