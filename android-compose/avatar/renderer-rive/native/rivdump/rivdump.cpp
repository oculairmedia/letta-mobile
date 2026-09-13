// rivdump: loads a .riv with rive-runtime and prints every artboard's object tree as JSON,
// one object per line, with every authored property the runtime can deserialize.
//
// The property list is data (schema.tsv from gen_schema.py), so this stays generic: for each
// object we read each property whose owning type the object isTypeOf(). Object indices are the
// artboard's own order, which is what parentId refers to.
//
// usage: rivdump <file.riv> <schema.tsv>
#include "rive/artboard.hpp"
#include "rive/file.hpp"
#include "rive/core.hpp"
#include "rive/generated/core_registry.hpp"
#include "utils/no_op_factory.hpp"

#include <cstdio>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

struct Prop
{
    int typeKey;
    int key;
    std::string kind;
    std::string typeName;
    std::string name;
};

static std::string json_escape(const std::string& s)
{
    std::string out;
    for (char c : s)
    {
        if (c == '"' || c == '\\') { out += '\\'; out += c; }
        else if (c == '\n') out += "\\n";
        else if ((unsigned char)c < 0x20) { char b[8]; snprintf(b, sizeof b, "\\u%04x", c); out += b; }
        else out += c;
    }
    return out;
}

int main(int argc, const char* argv[])
{
    if (argc < 3)
    {
        fprintf(stderr, "usage: rivdump <file.riv> <schema.tsv>\n");
        return 1;
    }
    std::ifstream in(argv[1], std::ios::binary);
    std::vector<uint8_t> bytes((std::istreambuf_iterator<char>(in)), {});
    if (bytes.empty())
    {
        fprintf(stderr, "cannot read %s\n", argv[1]);
        return 1;
    }

    std::vector<Prop> props;
    std::string typeNameOf[4096] = {};
    {
        std::ifstream schema(argv[2]);
        std::string line;
        while (std::getline(schema, line))
        {
            std::istringstream ss(line);
            Prop p;
            std::string tk, k;
            if (!(std::getline(ss, tk, '\t') && std::getline(ss, k, '\t') && std::getline(ss, p.kind, '\t') &&
                  std::getline(ss, p.typeName, '\t') && std::getline(ss, p.name, '\t')))
                continue;
            p.typeKey = std::stoi(tk);
            p.key = std::stoi(k);
            if (p.typeKey < 4096) typeNameOf[p.typeKey] = p.typeName;
            if (p.key >= 0)
                props.push_back(p);
        }
    }

    rive::NoOpFactory factory;
    rive::ImportResult result;
    auto file = rive::File::import(bytes, &factory, &result);
    if (!file)
    {
        fprintf(stderr, "import failed (%d)\n", (int)result);
        return 1;
    }

    printf("{\"artboards\":[\n");
    for (size_t a = 0; a < file->artboardCount(); ++a)
    {
        auto* artboard = file->artboard(a);
        printf("%s{\"name\":\"%s\",\"objects\":[\n", a ? "," : "", json_escape(artboard->name()).c_str());
        const auto& objects = artboard->objects();
        for (size_t i = 0; i < objects.size(); ++i)
        {
            rive::Core* object = objects[i];
            if (object == nullptr)
            {
                printf("%s{\"index\":%zu,\"type\":null}\n", i ? "," : "", i);
                continue;
            }
            int typeKey = object->coreType();
            printf("%s{\"index\":%zu,\"typeKey\":%d,\"type\":\"%s\"", i ? "," : "", i, typeKey,
                   typeKey < 4096 ? typeNameOf[typeKey].c_str() : "");
            for (const auto& p : props)
            {
                if (!object->isTypeOf((uint16_t)p.typeKey))
                    continue;
                printf(",\"%s\":", p.name.c_str());
                if (p.kind == "Double")
                    printf("%g", rive::CoreRegistry::getDouble(object, p.key));
                else if (p.kind == "Uint")
                    printf("%u", rive::CoreRegistry::getUint(object, p.key));
                else if (p.kind == "Bool")
                    printf("%s", rive::CoreRegistry::getBool(object, p.key) ? "true" : "false");
                else if (p.kind == "Color")
                    printf("\"%08X\"", (unsigned)rive::CoreRegistry::getColor(object, p.key));
                else if (p.kind == "String")
                    printf("\"%s\"", json_escape(rive::CoreRegistry::getString(object, p.key)).c_str());
                else
                    printf("null");
            }
            printf("}\n");
        }
        printf("]}\n");
    }
    printf("]}\n");
    return 0;
}
