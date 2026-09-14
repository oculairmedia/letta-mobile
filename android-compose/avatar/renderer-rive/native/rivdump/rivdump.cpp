// rivdump: loads a .riv with rive-runtime and prints every artboard as JSON: its object tree, its
// animations (keyed objects, properties, keyframes) and its state machines (inputs, layers, states,
// transitions, conditions, listeners, actions), every authored property the runtime can deserialize.
//
// The property list is data (schema.tsv from gen_schema.py), so this stays generic: for each
// object we read each property whose owning type the object isTypeOf(). Object indices are the
// artboard's own order, which is what parentId refers to. Timelines and machines are printed with
// the same generic printer, so a new runtime type shows up as soon as the schema knows it.
//
// usage: rivdump <file.riv> <schema.tsv>
#include "rive/artboard.hpp"
#include "rive/file.hpp"
#include "rive/core.hpp"
#include "rive/generated/core_registry.hpp"
#include "rive/animation/linear_animation.hpp"
#include "rive/animation/keyed_object.hpp"
#include "rive/animation/keyed_property.hpp"
#include "rive/animation/keyframe.hpp"
#include "rive/animation/state_machine.hpp"
#include "rive/animation/state_machine_input.hpp"
#include "rive/animation/state_machine_layer.hpp"
#include "rive/animation/layer_state.hpp"
#include "rive/animation/animation_state.hpp"
#include "rive/animation/state_transition.hpp"
#include "rive/animation/transition_condition.hpp"
#include "rive/animation/state_machine_listener.hpp"
#include "rive/animation/listener_action.hpp"
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

static std::vector<Prop> g_props;
static std::string g_typeNameOf[4096];

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

// Prints `{"type":..., <every schema property the object has>` and leaves the object open so the
// caller can append children, then closes it. `extra` is raw JSON appended before the close.
static void print_object(const rive::Core* object, const std::string& extra = "", bool close = true)
{
    if (object == nullptr)
    {
        printf("{\"type\":null}");
        return;
    }
    int typeKey = object->coreType();
    printf("{\"typeKey\":%d,\"type\":\"%s\"", typeKey, typeKey < 4096 ? g_typeNameOf[typeKey].c_str() : "");
    for (const auto& p : g_props)
    {
        if (!object->isTypeOf((uint16_t)p.typeKey))
            continue;
        printf(",\"%s\":", p.name.c_str());
        auto* o = const_cast<rive::Core*>(object);
        if (p.kind == "Double")
            printf("%g", rive::CoreRegistry::getDouble(o, p.key));
        else if (p.kind == "Uint")
            printf("%u", rive::CoreRegistry::getUint(o, p.key));
        else if (p.kind == "Bool")
            printf("%s", rive::CoreRegistry::getBool(o, p.key) ? "true" : "false");
        else if (p.kind == "Color")
            printf("\"%08X\"", (unsigned)rive::CoreRegistry::getColor(o, p.key));
        else if (p.kind == "String")
            printf("\"%s\"", json_escape(rive::CoreRegistry::getString(o, p.key)).c_str());
        else
            printf("null");
    }
    if (!extra.empty())
        printf(",%s", extra.c_str());
    if (close)
        printf("}");
}

static void print_animation(const rive::LinearAnimation* anim)
{
    print_object(anim, "", false);
    printf(",\"keyedObjects\":[\n");
    for (size_t k = 0; k < anim->numKeyedObjects(); ++k)
    {
        const rive::KeyedObject* ko = anim->getObject(k);
        printf("%s", k ? "," : "");
        print_object(ko, "", false);
        printf(",\"properties\":[");
        for (size_t p = 0; p < ko->numKeyedProperties(); ++p)
        {
            const rive::KeyedProperty* kp = ko->getProperty(p);
            printf("%s", p ? "," : "");
            print_object(kp, "", false);
            printf(",\"keyframes\":[");
            for (size_t f = 0; f < kp->numKeyFrames(); ++f)
            {
                printf("%s", f ? "," : "");
                print_object(kp->getKeyFrame(f));
            }
            printf("]}");
        }
        printf("]}\n");
    }
    printf("]}\n");
}

static int state_index(const rive::StateMachineLayer* layer, const rive::LayerState* state)
{
    for (size_t i = 0; i < layer->stateCount(); ++i)
        if (layer->state(i) == state)
            return (int)i;
    return -1;
}

static void print_state_machine(const rive::StateMachine* sm)
{
    print_object(sm, "", false);
    printf(",\"inputs\":[");
    for (size_t i = 0; i < sm->inputCount(); ++i)
    {
        printf("%s", i ? "," : "");
        print_object(sm->input(i));
    }
    printf("],\"listeners\":[\n");
    for (size_t i = 0; i < sm->listenerCount(); ++i)
    {
        const rive::StateMachineListener* l = sm->listener(i);
        printf("%s", i ? "," : "");
        print_object(l, "", false);
        printf(",\"actions\":[");
        for (size_t a = 0; a < l->actionCount(); ++a)
        {
            printf("%s", a ? "," : "");
            print_object(l->action(a));
        }
        printf("]}\n");
    }
    printf("],\"layers\":[\n");
    for (size_t li = 0; li < sm->layerCount(); ++li)
    {
        const rive::StateMachineLayer* layer = sm->layer(li);
        printf("%s", li ? "," : "");
        print_object(layer, "", false);
        printf(",\"states\":[\n");
        for (size_t si = 0; si < layer->stateCount(); ++si)
        {
            const rive::LayerState* st = layer->state(si);
            std::string extra = "\"stateIndex\":" + std::to_string(si);
            if (st->is<rive::AnimationState>())
            {
                auto* as = st->as<rive::AnimationState>();
                if (as->animation() != nullptr)
                    extra += ",\"animationName\":\"" + json_escape(as->animation()->name()) + "\"";
            }
            printf("%s", si ? "," : "");
            print_object(st, extra, false);
            printf(",\"transitions\":[");
            for (size_t ti = 0; ti < st->transitionCount(); ++ti)
            {
                const rive::StateTransition* tr = st->transition(ti);
                std::string tx = "\"toStateIndex\":" + std::to_string(state_index(layer, tr->stateTo()));
                printf("%s", ti ? "," : "");
                print_object(tr, tx, false);
                printf(",\"conditions\":[");
                for (size_t ci = 0; ci < tr->conditionCount(); ++ci)
                {
                    printf("%s", ci ? "," : "");
                    print_object(tr->condition(ci));
                }
                printf("]}");
            }
            printf("]}\n");
        }
        printf("]}\n");
    }
    printf("]}\n");
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
            if (p.typeKey < 4096) g_typeNameOf[p.typeKey] = p.typeName;
            if (p.key >= 0)
                g_props.push_back(p);
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
            printf("%s", i ? "," : "");
            std::string extra = "\"index\":" + std::to_string(i);
            print_object(objects[i], extra);
            printf("\n");
        }
        printf("],\"animations\":[\n");
        for (size_t i = 0; i < artboard->animationCount(); ++i)
        {
            printf("%s", i ? "," : "");
            print_animation(artboard->animation(i));
        }
        printf("],\"stateMachines\":[\n");
        for (size_t i = 0; i < artboard->stateMachineCount(); ++i)
        {
            printf("%s", i ? "," : "");
            print_state_machine(artboard->stateMachine(i));
        }
        printf("]}\n");
    }
    printf("]}\n");
    return 0;
}
