# External source trees

[overmorrow/](overmorrow/README.md) is a retained Flutter weather-app source tree;
its README identifies the upstream as `bmaroti9/Overmorrow`, and its
[LICENSE](overmorrow/LICENSE) is preserved. It is not included by the product
Gradle settings or imported by the Kotlin product modules. Its original reason
for vendoring and upstream revision are not recorded; do not assume it is a
runtime dependency or a verified design specification. CI path filters include
this directory, which does not establish a product dependency.

Production DrawBox lives in [android-compose/drawbox/](../android-compose/drawbox/),
with integration purpose, upstream version and local changes recorded in its
[VENDORED.md](../android-compose/drawbox/VENDORED.md).
