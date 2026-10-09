package meteordevelopment.meteorclient;

import com.google.gson.JsonParser;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reads bytecode without launching Minecraft or initializing its classes. */
public final class PortCompatibilityTest {
    private static final Map<String, ClassNode> classes = new HashMap<>();
    private static final List<String> failures = new ArrayList<>();
    private static final Map<String, String> invasiveHooks = new HashMap<>();
    private static int checks;

    public static void main(String[] args) throws Exception {
        var loader = PortCompatibilityTest.class.getClassLoader();
        var metadata = JsonParser.parseReader(new InputStreamReader(loader.getResourceAsStream("fabric.mod.json"))).getAsJsonObject();
        check(metadata.getAsJsonObject("depends").get("minecraft").getAsString().equals("~26.3"), "Minecraft metadata");
        check(!metadata.getAsJsonObject("depends").has("viafabricplus"), "VFP remains optional");
        for (var entry : metadata.getAsJsonArray("mixins")) {
            var config = JsonParser.parseReader(new InputStreamReader(loader.getResourceAsStream(entry.getAsString()))).getAsJsonObject();
            for (String side : List.of("client", "mixins")) {
                if (!config.has(side)) continue;
                for (var name : config.getAsJsonArray(side)) {
                    verifyMixin(config.get("package").getAsString() + "." + name.getAsString());
                }
            }
        }
        var helper = read("meteordevelopment/meteorclient/utils/network/ViaFabricPlusCompat");
        check(helper != null, "eBounce protocol helper retained");
        check(read("meteordevelopment/meteorclient/mixin/KeyboardInputMixin").methods.stream().anyMatch(m -> m.name.equals("restoreBounceInput")), "Inventory-open eBounce hook retained");
        check(read("meteordevelopment/meteorclient/mixin/ClientPacketListenerMixin").methods.stream().anyMatch(m -> m.name.toLowerCase().contains("chat")), "Portal chat hook retained");
        checkCalls("gui/widgets/input/WTextBox", "setFocused", "com/mojang/blaze3d/platform/TextInputManager", "onTextInputFocusChange", "(Ljava/lang/Object;Z)V");
        checkCalls("gui/themes/meteor/MeteorGuiTheme", "scale", "com/mojang/blaze3d/platform/Window", "getPixelDensity", "()F");
        if (args.length > 0) inspectWurst(args[0]);
        System.out.println("Port bytecode checks: " + checks + ", failures: " + failures.size());
        failures.forEach(System.err::println);
        if (!failures.isEmpty()) throw new AssertionError("Port compatibility checks failed");
    }

    private static void checkCalls(String type, String method, String owner, String target, String descriptor) throws Exception {
        boolean found = false;
        for (var m : read("meteordevelopment/meteorclient/" + type).methods) {
            if (!m.name.equals(method)) continue;
            for (var instruction : m.instructions) {
                if (instruction instanceof MethodInsnNode call && call.owner.equals(owner)
                    && call.name.equals(target) && call.desc.equals(descriptor)) found = true;
            }
        }
        check(found, type + "." + method + " must call " + target);
        check(hasMethod(read(owner), target, descriptor), "Missing GUI integration API " + owner + "." + target);
    }

    private static void verifyMixin(String name) throws Exception {
        ClassNode mixin = read(name.replace('.', '/'));
        check(mixin != null, "Missing mixin " + name);
        if (mixin == null) return;
        AnnotationNode annotation = annotations(mixin.visibleAnnotations, mixin.invisibleAnnotations).stream()
            .filter(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")).findFirst().orElseThrow();
        List<String> targets = new ArrayList<>();
        for (Object type : list(value(annotation, "value"))) targets.add(((Type) type).getInternalName());
        for (Object type : list(value(annotation, "targets"))) targets.add(type.toString().replace('.', '/'));
        recordInvasiveHooks(mixin, targets, invasiveHooks);
        boolean optional = annotations(mixin.visibleAnnotations, mixin.invisibleAnnotations).stream().anyMatch(a -> a.desc.endsWith("/Pseudo;"));
        for (String targetName : targets) {
            ClassNode target = read(targetName);
            if (target == null && optional) continue;
            check(target != null, name + " missing target " + targetName);
            if (target == null) continue;
            for (var field : mixin.fields) {
                for (var a : annotations(field.visibleAnnotations, field.invisibleAnnotations)) {
                    if (a.desc.endsWith("/Shadow;")) check(hasField(target, field.name, field.desc), name + " missing shadow " + field.name + field.desc);
                }
            }
            for (MethodNode method : mixin.methods) {
                for (var a : annotations(method.visibleAnnotations, method.invisibleAnnotations)) {
                    if (a.desc.endsWith("/Shadow;")) check(hasMethod(target, method.name.replaceFirst("^shadow\\$", ""), method.desc), name + " missing shadow " + method.name + method.desc);
                    if (a.desc.endsWith("/Accessor;") && value(a, "value") instanceof String field) {
                        check(hasField(target, field, null), name + " missing accessor " + field);
                    }
                    if (a.desc.endsWith("/Invoker;") && value(a, "value") instanceof String invoked) {
                        check(hasMethod(target, invoked, null), name + " missing invoker " + invoked);
                    }
                    if (value(a, "require") instanceof Integer count && count == 0) continue;
                    for (Object point : list(value(a, "at"))) {
                        if (!(point instanceof AnnotationNode at) || !(value(at, "target") instanceof String member) || !member.startsWith("L")) continue;
                        int ownerEnd = member.indexOf(';');
                        if (ownerEnd < 0) continue;
                        ClassNode owner = read(member.substring(1, ownerEnd));
                        String signature = member.substring(ownerEnd + 1);
                        int descriptor = signature.indexOf('(');
                        if (descriptor >= 0) {
                            check(hasMethod(owner, signature.substring(0, descriptor), signature.substring(descriptor)), name + " missing @At method " + member);
                        } else if (signature.contains(":")) {
                            String[] parts = signature.split(":", 2);
                            check(hasField(owner, parts[0], parts[1]), name + " missing @At field " + member);
                        }
                    }
                    for (Object selector : list(value(a, "method"))) {
                        String s = selector.toString();
                        int desc = s.indexOf('(');
                        String methodName = desc < 0 ? s : s.substring(0, desc);
                        if (methodName.contains("*") || methodName.startsWith("L")) continue;
                        check(hasMethod(target, methodName, desc < 0 ? null : s.substring(desc)), name + " missing injection method " + s);
                        if (a.desc.endsWith("/Inject;")) {
                            Type[] args = Type.getArgumentTypes(method.desc);
                            int callback = 0;
                            while (callback < args.length && !args[callback].getDescriptor().startsWith("Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo")) callback++;
                            if (callback < args.length && callback > 0) {
                                check(matchesCallback(target, methodName, args, callback), name + " wrong callback parameters " + method.name + method.desc);
                            }
                        }
                    }
                }
            }
        }
    }

    private static boolean hasMethod(ClassNode type, String name, String descriptor) throws Exception {
        if (type == null) return false;
        if (type.methods.stream().anyMatch(m -> m.name.equals(name) && (descriptor == null || m.desc.equals(descriptor)))) return true;
        if (hasMethod(read(type.superName), name, descriptor)) return true;
        for (String parent : type.interfaces) if (hasMethod(read(parent), name, descriptor)) return true;
        return false;
    }

    private static void recordInvasiveHooks(ClassNode mixin, List<String> targets, Map<String, String> hooks) {
        for (MethodNode method : mixin.methods) {
            for (var a : annotations(method.visibleAnnotations, method.invisibleAnnotations)) {
                if (a.desc.endsWith("/Overwrite;")) {
                    for (String target : targets) hooks.put(target + "#" + method.name + "#overwrite", mixin.name);
                } else if (a.desc.endsWith("/Redirect;")) {
                    for (Object selector : list(value(a, "method"))) {
                        for (Object point : list(value(a, "at"))) {
                            if (!(point instanceof AnnotationNode at)) continue;
                            for (String target : targets) hooks.put(target + "#" + selector.toString().split("\\(", 2)[0] + "#" + value(at, "target"), mixin.name);
                        }
                    }
                }
            }
        }
    }

    private static void inspectWurst(String file) throws Exception {
        Map<String, String> wurstHooks = new HashMap<>();
        try (var jar = new java.util.jar.JarFile(file)) {
            var metadata = JsonParser.parseReader(new InputStreamReader(jar.getInputStream(jar.getEntry("fabric.mod.json")))).getAsJsonObject();
            check(!metadata.getAsJsonObject("breaks").has("meteor-client"), "Wurst has no declared Meteor conflict");
            for (var entry : jar.stream().filter(e -> e.getName().startsWith("net/wurstclient/mixin/") && e.getName().endsWith(".class")).toList()) {
                ClassNode mixin = new ClassNode();
                new ClassReader(jar.getInputStream(entry)).accept(mixin, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                for (var a : annotations(mixin.visibleAnnotations, mixin.invisibleAnnotations)) {
                    if (!a.desc.endsWith("/Mixin;")) continue;
                    List<String> targets = new ArrayList<>();
                    for (Object type : list(value(a, "value"))) targets.add(((Type) type).getInternalName());
                    for (Object type : list(value(a, "targets"))) targets.add(type.toString().replace('.', '/'));
                    recordInvasiveHooks(mixin, targets, wurstHooks);
                }
            }
        }
        int overlaps = 0;
        for (var hook : wurstHooks.entrySet()) {
            if (!invasiveHooks.containsKey(hook.getKey())) continue;
            overlaps++;
            System.out.println("Potential competing Wurst hook: " + hook.getKey() + " (" + invasiveHooks.get(hook.getKey()) + ", " + hook.getValue() + ")");
        }
        System.out.println("Wurst direct overwrite/redirect overlaps: " + overlaps + "; this does not prove runtime interoperability.");
    }

    private static boolean matchesCallback(ClassNode type, String name, Type[] handler, int count) throws Exception {
        if (type == null) return false;
        for (MethodNode method : type.methods) {
            if (!method.name.equals(name)) continue;
            Type[] args = Type.getArgumentTypes(method.desc);
            if (args.length < count) continue;
            boolean match = true;
            for (int i = 0; i < count; i++) {
                if (!handler[i].equals(args[i]) && !handler[i].getDescriptor().equals("Ljava/lang/Object;")) match = false;
            }
            if (match) return true;
        }
        return matchesCallback(read(type.superName), name, handler, count);
    }

    private static boolean hasField(ClassNode type, String name, String descriptor) throws Exception {
        if (type == null) return false;
        if (type.fields.stream().anyMatch(f -> f.name.equals(name) && (descriptor == null || f.desc.equals(descriptor)))) return true;
        return hasField(read(type.superName), name, descriptor);
    }

    private static ClassNode read(String name) throws Exception {
        if (name == null) return null;
        if (classes.containsKey(name)) return classes.get(name);
        try (var stream = PortCompatibilityTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            ClassNode node = null;
            if (stream != null) {
                node = new ClassNode();
                new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
            classes.put(name, node);
            return node;
        }
    }

    private static Object value(AnnotationNode annotation, String key) {
        if (annotation.values == null) return null;
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (annotation.values.get(i).equals(key)) return annotation.values.get(i + 1);
        }
        return null;
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> list ? list : value == null ? List.of() : List.of(value);
    }

    private static List<AnnotationNode> annotations(List<AnnotationNode> visible, List<AnnotationNode> invisible) {
        List<AnnotationNode> result = new ArrayList<>();
        if (visible != null) result.addAll(visible);
        if (invisible != null) result.addAll(invisible);
        return result;
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) failures.add(message);
    }
}
