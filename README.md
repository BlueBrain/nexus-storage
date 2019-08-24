[![Join the chat at https://gitter.im/BlueBrain/nexus](https://badges.gitter.im/BlueBrain/nexus.svg)](https://gitter.im/BlueBrain/nexus?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)
[![Build Status](https://bbpcode.epfl.ch/ci/buildStatus/icon?job=nexus.sbt.nexus-iam)](https://bbpcode.epfl.ch/ci/job/nexus.sbt.nexus-storage)
[![GitHub release](https://img.shields.io/github/release/BlueBrain/nexus-storage.svg)]()

# Nexus Storage Service

A service to abstract I/O operations on a remote file system, to support Nexus' file management API.

Please visit the [parent project](https://github.com/BlueBrain/nexus) for more information about Nexus.

## Build

In the project's top-level directory run:

```shell script
./sbt assembly
```

This outputs a self-contained JAR `nexus-storage.jar`.

## Getting involved
 There are several channels provided to address different issues:
- **Feature request**: If there is a feature you would like to see in Blue Brain Nexus Storage Service, please first consult the [list of open feature requests](https://github.com/BlueBrain/nexus/issues?q=is%3Aopen+is%3Aissue+label%3Afeature+label%3Astorage). In case there isn't already one, please [open a feature request](https://github.com/BlueBrain/nexus/issues/new?labels=feature,storage) with a detailed explanation.
- **Questions**: For any questions please visit the [documentation](https://bluebrainnexus.io/docs/api/). Alternatively, you can find us on our [Gitter channel](https://gitter.im/BlueBrain/nexus).
- **Bug report**: If you have found a bug, please [create an issue](https://github.com/BlueBrain/nexus/issues/new?labels=bug,storage).
