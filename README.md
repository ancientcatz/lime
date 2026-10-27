# Lime

Lime is an [Xposed](https://github.com/libxposed/api) module that pins screen orientation on a
per-app basis. Useful for apps that need a forced orientation to give the best experience (e.g.
forcing a game into landscape).

Built against the modern libxposed API 102.

## Target platform

| Platform      | Version                          |
|:--------------|:---------------------------------|
| Android       | minSdk 30 / targetSdk 33 (13+)   |
| libxposed API | 102 (`minApiVersion=101`)        |

## Module scope

The module ships with an empty `META-INF/xposed/scope.list`. Configure the target apps in
NPatch's module settings (one package per line).

## License

GNU General Public License v3. See [LICENSE](LICENSE).
