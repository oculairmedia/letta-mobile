#include <node.h>

#include <unistd.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

// Tiny launcher around nodejs-mobile's libnode. When invoked as `node` it
// behaves like the node CLI. When invoked as `npm` (argv[0] basename) it
// re-execs itself as `node` with the bundled npm-cli.js prepended, so local
// tools get a real package manager on PATH despite the noexec data partition
// (a plain shell-script wrapper in files/ can't be exec'd). The npm-cli.js
// path and the real node binary path are provided via LETTA_NPM_CLI_JS and
// LETTA_NODE_BIN. See letta-mobile-iq24j.
//
// We re-exec via execv (instead of calling node::Start with a hand-built argv)
// because nodejs-mobile's node::Start treats argv as kernel-provided, mutable,
// contiguous memory; passing a synthesized array segfaults. Letting the OS set
// up argv for a fresh `node <npm-cli.js> ...` process avoids that entirely.
int main(int argc, char** argv) {
    const char* invoked = argc > 0 ? argv[0] : "node";
    const char* slash = std::strrchr(invoked, '/');
    const char* base = slash ? slash + 1 : invoked;

    const char* npmCli = std::getenv("LETTA_NPM_CLI_JS");
    const char* nodeBin = std::getenv("LETTA_NODE_BIN");
    if (std::strcmp(base, "npm") == 0 && npmCli != nullptr && npmCli[0] != '\0' &&
        nodeBin != nullptr && nodeBin[0] != '\0') {
        std::vector<char*> args;
        args.push_back(const_cast<char*>(nodeBin));
        args.push_back(const_cast<char*>(npmCli));
        for (int i = 1; i < argc; ++i) {
            args.push_back(argv[i]);
        }
        args.push_back(nullptr);
        execv(nodeBin, args.data());
        // execv only returns on failure.
        std::perror("embedded npm launcher: execv failed");
        return 127;
    }
    return node::Start(argc, argv);
}
