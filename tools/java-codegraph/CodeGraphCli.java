import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public final class CodeGraphCli {
    private static final String SCHEMA_VERSION = "coding-agent-codegraph/v1";

    private record Node(String id, String kind, String name, String qualifiedName, String module,
                        String file, long line, List<String> annotations, Map<String, Object> attributes) {}

    private record Edge(String source, String target, String kind, String resolution,
                        String file, long line, String evidence) {}

    private record Graph(Path root, int discoveredFiles, int parsedFiles, List<String> failedFiles,
                         List<Node> nodes, List<Edge> edges, List<String> diagnostics, String digest) {}

    private CodeGraphCli() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || "help".equals(args[0]) || "--help".equals(args[0])) {
            usage();
            return;
        }
        String command = args[0];
        Map<String, String> options = parseOptions(args, 1);
        Path root = realDirectory(options.getOrDefault("root", "."));
        Graph graph = buildGraph(root);
        if ("build".equals(command)) {
            String output = required(options, "output");
            writeExclusive(Path.of(output), json(graphDocument(graph)) + "\n");
            System.out.println(json(Map.of(
                    "status", graph.failedFiles().isEmpty() ? "built" : "partial",
                    "discoveredFiles", graph.discoveredFiles(),
                    "parsedFiles", graph.parsedFiles(),
                    "failedFiles", graph.failedFiles().size(),
                    "nodes", graph.nodes().size(),
                    "edges", graph.edges().size(),
                    "digest", graph.digest(),
                    "output", Path.of(output).toAbsolutePath().normalize().toString())));
            if (!graph.failedFiles().isEmpty()) System.exit(4);
            return;
        }
        if ("query".equals(command)) {
            boolean hasSymbol = options.containsKey("symbol");
            boolean hasFile = options.containsKey("file");
            if (hasSymbol == hasFile) throw new IllegalArgumentException("query requires exactly one of --symbol or --file");
            Map<String, Object> result = hasSymbol
                    ? querySymbol(graph, options.get("symbol"))
                    : queryFile(graph, normalizeRelative(options.get("file")));
            String rendered = json(result) + "\n";
            if (options.containsKey("output")) writeExclusive(Path.of(options.get("output")), rendered);
            else System.out.print(rendered);
            String status = String.valueOf(result.get("status"));
            if ("ambiguous".equals(status)) System.exit(3);
            if ("not_found".equals(status)) System.exit(2);
            return;
        }
        throw new IllegalArgumentException("unknown command: " + command);
    }

    private static void usage() {
        System.out.println("""
                Java code graph (JDK 21, zero third-party dependencies)

                Usage:
                  java tools/java-codegraph/CodeGraphCli.java build --root <repo> --output <graph.json>
                  java tools/java-codegraph/CodeGraphCli.java query --root <repo> --symbol <name> [--output <file>]
                  java tools/java-codegraph/CodeGraphCli.java query --root <repo> --file <relative-path> [--output <file>]
                """);
    }

    private static Map<String, String> parseOptions(String[] args, int start) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int index = start; index < args.length; index += 2) {
            String key = args[index];
            if (!key.startsWith("--") || index + 1 >= args.length) {
                throw new IllegalArgumentException("options must use --key value pairs");
            }
            key = key.substring(2);
            if (options.putIfAbsent(key, args[index + 1]) != null) {
                throw new IllegalArgumentException("duplicate option --" + key);
            }
        }
        Set<String> allowed = Set.of("root", "output", "symbol", "file");
        for (String key : options.keySet()) if (!allowed.contains(key)) throw new IllegalArgumentException("unknown option --" + key);
        return options;
    }

    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing --" + key);
        return value;
    }

    private static Path realDirectory(String value) throws IOException {
        Path root = Path.of(value).toRealPath();
        if (!Files.isDirectory(root)) throw new IllegalArgumentException("root is not a directory: " + root);
        return root;
    }

    private static Graph buildGraph(Path root) throws IOException {
        List<Path> files;
        try (Stream<Path> stream = Files.walk(root)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(file -> !Files.isSymbolicLink(file))
                    .filter(file -> file.getFileName().toString().endsWith(".java"))
                    .filter(file -> !containsIgnoredSegment(root.relativize(file)))
                    .sorted()
                    .toList();
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("JDK compiler is unavailable; run with a JDK, not a JRE");
        DiagnosticCollector<JavaFileObject> diagnosticCollector = new DiagnosticCollector<>();
        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        int parsed = 0;
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(diagnosticCollector, Locale.ROOT, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> inputs = manager.getJavaFileObjectsFromPaths(files);
            JavacTask task = (JavacTask) compiler.getTask(null, manager, diagnosticCollector,
                    List.of("-proc:none", "-Xlint:none"), null, inputs);
            Trees trees = Trees.instance(task);
            SourcePositions positions = trees.getSourcePositions();
            for (CompilationUnitTree unit : task.parse()) {
                Path file = Path.of(unit.getSourceFile().toUri()).toRealPath();
                try {
                    new GraphScanner(root, file, unit, positions, nodes, edges).scan(unit, null);
                    parsed += 1;
                } catch (RuntimeException error) {
                    failed.add(normalizeRelative(root.relativize(file).toString()) + ": " + error.getMessage());
                }
            }
        }
        List<String> diagnostics = diagnosticCollector.getDiagnostics().stream()
                .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                .limit(200)
                .map(CodeGraphCli::formatDiagnostic)
                .sorted()
                .toList();
        nodes.sort(Comparator.comparing(Node::id).thenComparing(Node::file).thenComparingLong(Node::line));
        edges.sort(Comparator.comparing(Edge::source).thenComparing(Edge::kind).thenComparing(Edge::target)
                .thenComparing(Edge::file).thenComparingLong(Edge::line));
        Map<String, Object> digestPayload = new LinkedHashMap<>();
        digestPayload.put("discoveredFiles", files.size());
        digestPayload.put("parsedFiles", parsed);
        digestPayload.put("failedFiles", failed);
        digestPayload.put("nodes", nodes.stream().map(CodeGraphCli::nodeMap).toList());
        digestPayload.put("edges", edges.stream().map(CodeGraphCli::edgeMap).toList());
        return new Graph(root, files.size(), parsed, List.copyOf(failed), List.copyOf(nodes), List.copyOf(edges), diagnostics,
                "sha256:" + hexDigest(canonicalJson(digestPayload)));
    }

    private static boolean containsIgnoredSegment(Path relative) {
        for (Path part : relative) {
            if (Set.of(".git", "target", "node_modules", ".idea", ".vscode").contains(part.toString())) return true;
        }
        return false;
    }

    private static String formatDiagnostic(Diagnostic<? extends JavaFileObject> diagnostic) {
        String source = diagnostic.getSource() == null ? "<unknown>" : Path.of(diagnostic.getSource().toUri()).getFileName().toString();
        return source + ":" + diagnostic.getLineNumber() + ": " + diagnostic.getMessage(Locale.ROOT);
    }

    private static final class GraphScanner extends TreePathScanner<Void, Void> {
        private final Path root;
        private final String file;
        private final String module;
        private final CompilationUnitTree unit;
        private final SourcePositions positions;
        private final List<Node> nodes;
        private final List<Edge> edges;
        private final String packageName;
        private final List<String> imports;
        private final Deque<String> classStack = new ArrayDeque<>();
        private final Deque<String> methodStack = new ArrayDeque<>();

        private GraphScanner(Path root, Path file, CompilationUnitTree unit, SourcePositions positions,
                             List<Node> nodes, List<Edge> edges) {
            this.root = root;
            this.file = normalizeRelative(root.relativize(file).toString());
            this.module = moduleOf(this.file);
            this.unit = unit;
            this.positions = positions;
            this.nodes = nodes;
            this.edges = edges;
            this.packageName = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
            this.imports = unit.getImports().stream().map(ImportTree::getQualifiedIdentifier).map(Object::toString).sorted().toList();
        }

        @Override
        public Void visitClass(ClassTree tree, Void unused) {
            String simpleName = tree.getSimpleName().toString();
            if (simpleName.isBlank()) return super.visitClass(tree, unused);
            String qualified = classStack.isEmpty()
                    ? (packageName.isBlank() ? simpleName : packageName + "." + simpleName)
                    : classStack.peek() + "$" + simpleName;
            String id = "type:" + qualified;
            List<String> annotations = annotations(tree.getModifiers().getAnnotations());
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("typeKind", tree.getKind().name().toLowerCase(Locale.ROOT));
            attributes.put("test", file.contains("/src/test/") || file.endsWith("Test.java"));
            nodes.add(new Node(id, "type", simpleName, qualified, module, file, line(tree), annotations, attributes));
            for (String imported : imports) addEdge(id, "type:" + imported, "imports", "syntactic", tree, imported);
            if (tree.getExtendsClause() != null) addEdge(id, "type:" + tree.getExtendsClause(), "extends", "syntactic", tree, tree.getExtendsClause().toString());
            for (Tree implemented : tree.getImplementsClause()) addEdge(id, "type:" + implemented, "implements", "syntactic", tree, implemented.toString());
            classStack.push(qualified);
            try { return super.visitClass(tree, unused); }
            finally { classStack.pop(); }
        }

        @Override
        public Void visitMethod(MethodTree tree, Void unused) {
            if (classStack.isEmpty()) return super.visitMethod(tree, unused);
            String owner = classStack.peek();
            String name = tree.getName().toString();
            String signature = String.join(",", tree.getParameters().stream().map(parameter -> parameter.getType().toString()).toList());
            String qualified = owner + "#" + name + "(" + signature + ")";
            String id = "method:" + qualified;
            List<String> annotations = annotations(tree.getModifiers().getAnnotations());
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("returnType", tree.getReturnType() == null ? null : tree.getReturnType().toString());
            attributes.put("parameters", tree.getParameters().stream().map(parameter -> parameter.getType().toString()).toList());
            nodes.add(new Node(id, "method", name, qualified, module, file, line(tree), annotations, attributes));
            addEdge("type:" + owner, id, "declares", "resolved", tree, name);
            for (VariableTree parameter : tree.getParameters()) {
                addEdge(id, "type:" + parameter.getType(), "parameter_type", "syntactic", parameter, parameter.toString());
            }
            addEndpoints(owner, id, tree, annotations);
            methodStack.push(id);
            try { return super.visitMethod(tree, unused); }
            finally { methodStack.pop(); }
        }

        @Override
        public Void visitVariable(VariableTree tree, Void unused) {
            if (!classStack.isEmpty() && methodStack.isEmpty()) {
                addEdge("type:" + classStack.peek(), "type:" + tree.getType(), "field_dependency", "syntactic", tree, tree.toString());
            }
            return super.visitVariable(tree, unused);
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree tree, Void unused) {
            if (!methodStack.isEmpty()) {
                String select = tree.getMethodSelect().toString();
                addEdge(methodStack.peek(), "call:" + select, "calls", "syntactic", tree, select);
            }
            return super.visitMethodInvocation(tree, unused);
        }

        private void addEndpoints(String owner, String methodId, MethodTree tree, List<String> methodAnnotations) {
            for (AnnotationTree annotation : tree.getModifiers().getAnnotations()) {
                String annotationName = simpleAnnotation(annotation.getAnnotationType().toString());
                String httpMethod = switch (annotationName) {
                    case "GetMapping" -> "GET";
                    case "PostMapping" -> "POST";
                    case "PutMapping" -> "PUT";
                    case "DeleteMapping" -> "DELETE";
                    case "PatchMapping" -> "PATCH";
                    case "RequestMapping" -> "REQUEST";
                    default -> null;
                };
                if (httpMethod == null) continue;
                String route = annotation.getArguments().isEmpty() ? "/" : annotation.getArguments().toString();
                String endpointId = "endpoint:" + httpMethod + ":" + owner + "#" + tree.getName() + ":" + route;
                nodes.add(new Node(endpointId, "endpoint", httpMethod + " " + route, endpointId.substring(9), module, file,
                        line(tree), methodAnnotations, Map.of("httpMethod", httpMethod, "routeExpression", route)));
                addEdge(methodId, endpointId, "declares_endpoint", "resolved", tree, annotation.toString());
            }
        }

        private void addEdge(String source, String target, String kind, String resolution, Tree tree, String evidence) {
            edges.add(new Edge(source, target, kind, resolution, file, line(tree), evidence));
        }

        private long line(Tree tree) {
            long offset = positions.getStartPosition(unit, tree);
            return offset < 0 ? -1 : unit.getLineMap().getLineNumber(offset);
        }
    }

    private static List<String> annotations(List<? extends AnnotationTree> annotations) {
        return annotations.stream().map(annotation -> simpleAnnotation(annotation.getAnnotationType().toString())).sorted().toList();
    }

    private static String simpleAnnotation(String value) {
        int separator = value.lastIndexOf('.');
        return separator < 0 ? value : value.substring(separator + 1);
    }

    private static String moduleOf(String relative) {
        int source = relative.indexOf("/src/");
        if (source > 0) return relative.substring(0, source);
        int slash = relative.indexOf('/');
        return slash < 0 ? "root" : relative.substring(0, slash);
    }

    private static Map<String, Object> graphDocument(Graph graph) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("schemaVersion", SCHEMA_VERSION);
        document.put("generatedAt", Instant.now().toString());
        document.put("root", ".");
        document.put("digest", graph.digest());
        document.put("stats", Map.of(
                "discoveredFiles", graph.discoveredFiles(),
                "parsedFiles", graph.parsedFiles(),
                "failedFiles", graph.failedFiles().size(),
                "nodes", graph.nodes().size(),
                "edges", graph.edges().size()));
        document.put("failedFiles", graph.failedFiles());
        document.put("diagnostics", graph.diagnostics());
        document.put("nodes", graph.nodes().stream().map(CodeGraphCli::nodeMap).toList());
        document.put("edges", graph.edges().stream().map(CodeGraphCli::edgeMap).toList());
        return document;
    }

    private static Map<String, Object> nodeMap(Node node) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", node.id());
        value.put("kind", node.kind());
        value.put("name", node.name());
        value.put("qualifiedName", node.qualifiedName());
        value.put("module", node.module());
        value.put("file", node.file());
        value.put("line", node.line());
        value.put("annotations", node.annotations());
        value.put("attributes", node.attributes());
        return value;
    }

    private static Map<String, Object> edgeMap(Edge edge) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("source", edge.source());
        value.put("target", edge.target());
        value.put("kind", edge.kind());
        value.put("resolution", edge.resolution());
        value.put("file", edge.file());
        value.put("line", edge.line());
        value.put("evidence", edge.evidence());
        return value;
    }

    private static Map<String, Object> querySymbol(Graph graph, String symbol) {
        String needle = symbol.trim();
        if (needle.isEmpty()) throw new IllegalArgumentException("symbol may not be empty");
        List<Node> exact = graph.nodes().stream().filter(node -> node.qualifiedName().equals(needle) || node.id().equals(needle)).toList();
        List<Node> matches = exact.isEmpty()
                ? graph.nodes().stream().filter(node -> node.name().equals(needle)).toList()
                : exact;
        if (matches.isEmpty()) return Map.of("schemaVersion", "coding-agent-codegraph-query/v1", "status", "not_found", "query", needle);
        if (matches.size() > 1) return Map.of(
                "schemaVersion", "coding-agent-codegraph-query/v1", "status", "ambiguous", "query", needle,
                "candidates", matches.stream().map(CodeGraphCli::nodeMap).toList());
        Node selected = matches.getFirst();
        String simple = selected.name();
        List<Edge> outgoing = graph.edges().stream().filter(edge -> edge.source().equals(selected.id())).toList();
        List<Edge> incoming = graph.edges().stream().filter(edge -> edge.target().equals(selected.id()) || edge.target().endsWith("." + simple)
                || edge.target().endsWith("#" + simple) || edge.target().endsWith(":" + simple)).toList();
        Set<String> relatedFiles = new LinkedHashSet<>();
        relatedFiles.add(selected.file());
        incoming.forEach(edge -> relatedFiles.add(edge.file()));
        outgoing.forEach(edge -> relatedFiles.add(edge.file()));
        List<Node> relatedTests = graph.nodes().stream()
                .filter(node -> Boolean.TRUE.equals(node.attributes().get("test")))
                .filter(node -> graph.edges().stream().anyMatch(edge -> edge.file().equals(node.file()) && edge.target().contains(simple)))
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", "coding-agent-codegraph-query/v1");
        result.put("status", "found");
        result.put("query", needle);
        result.put("node", nodeMap(selected));
        result.put("outgoing", outgoing.stream().map(CodeGraphCli::edgeMap).toList());
        result.put("incoming", incoming.stream().map(CodeGraphCli::edgeMap).toList());
        result.put("relatedFiles", relatedFiles.stream().sorted().toList());
        result.put("relatedTests", relatedTests.stream().map(CodeGraphCli::nodeMap).toList());
        result.put("limitations", List.of("syntactic edges are evidence, not classpath-resolved call targets"));
        return result;
    }

    private static Map<String, Object> queryFile(Graph graph, String file) {
        List<Node> nodes = graph.nodes().stream().filter(node -> node.file().equals(file)).toList();
        if (nodes.isEmpty()) return Map.of("schemaVersion", "coding-agent-codegraph-query/v1", "status", "not_found", "query", file);
        Set<String> ids = nodes.stream().map(Node::id).collect(java.util.stream.Collectors.toSet());
        List<Edge> edges = graph.edges().stream().filter(edge -> edge.file().equals(file) || ids.contains(edge.source()) || ids.contains(edge.target())).toList();
        return Map.of(
                "schemaVersion", "coding-agent-codegraph-query/v1", "status", "found", "query", file,
                "nodes", nodes.stream().map(CodeGraphCli::nodeMap).toList(),
                "edges", edges.stream().map(CodeGraphCli::edgeMap).toList());
    }

    private static String normalizeRelative(String value) {
        String normalized = value.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains("../") || normalized.equals("..")) {
            throw new IllegalArgumentException("path must be repository-relative");
        }
        return normalized;
    }

    private static void writeExclusive(Path output, String content) throws IOException {
        Path absolute = output.toAbsolutePath().normalize();
        if (absolute.getParent() != null) Files.createDirectories(absolute.getParent());
        Files.writeString(absolute, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static String hexDigest(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder();
            for (byte item : digest) output.append(String.format("%02x", item));
            return output.toString();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof String text) return "\"" + escape(text) + "\"";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) parts.add(json(String.valueOf(entry.getKey())) + ":" + json(entry.getValue()));
            return "{" + String.join(",", parts) + "}";
        }
        if (value instanceof Collection<?> collection) return "[" + String.join(",", collection.stream().map(CodeGraphCli::json).toList()) + "]";
        if (value.getClass().isArray()) {
            List<Object> items = new ArrayList<>();
            int length = java.lang.reflect.Array.getLength(value);
            for (int index = 0; index < length; index++) items.add(java.lang.reflect.Array.get(value, index));
            return json(items);
        }
        return json(value.toString());
    }

    private static String canonicalJson(Object value) {
        if (value instanceof Map<?, ?> map) {
            List<String> keys = map.keySet().stream().map(String::valueOf).sorted().toList();
            List<String> parts = new ArrayList<>();
            for (String key : keys) parts.add(json(key) + ":" + canonicalJson(map.get(key)));
            return "{" + String.join(",", parts) + "}";
        }
        if (value instanceof Collection<?> collection) {
            return "[" + String.join(",", collection.stream().map(CodeGraphCli::canonicalJson).toList()) + "]";
        }
        if (value != null && value.getClass().isArray()) {
            List<Object> items = new ArrayList<>();
            int length = java.lang.reflect.Array.getLength(value);
            for (int index = 0; index < length; index++) items.add(java.lang.reflect.Array.get(value, index));
            return canonicalJson(items);
        }
        return json(value);
    }

    private static String escape(String value) {
        StringBuilder output = new StringBuilder();
        for (char character : value.toCharArray()) {
            switch (character) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (character < 0x20) output.append(String.format("\\u%04x", (int) character));
                    else output.append(character);
                }
            }
        }
        return output.toString();
    }
}
