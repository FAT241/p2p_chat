import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import javax.tools.ToolProvider;

/**
 * Trình biên dịch và chạy bằng JDK thuần (không cần Maven, không đóng gói exe, không file .bat).
 * Sử dụng:
 *   java Build.java            # biên dịch toàn bộ source code (server, client, common)
 *   java Build.java test       # chạy kiểm thử TCP, UDP, media signaling và giao diện Swing
 *   java Build.java server     # chạy Server danh bạ (server.ServerMain)
 *   java Build.java client     # chạy Client chat (client.ClientMain)
 *   java Build.java demo5      # mở 1 Server + 5 Client (An, Binh, Cuong, Dung, Giang) để chấm thi
 *   java Build.java demo6      # mở 1 Server + 6 Client để chấm thi
 */
public class Build {
    private static final Path ROOT = Path.of("").toAbsolutePath().normalize();
    private static final Path CLASSES = ROOT.resolve("build/classes");
    private static final Path TESTS = ROOT.resolve("build/test-classes");
    private static final Path JAR = ROOT.resolve("dist/lib/p2p-chat.jar");

    public static void main(String[] args) throws Exception {
        String task = args.length == 0 ? "build" : args[0];
        if (!Set.of("build", "test", "run", "client", "server", "demo", "demo5", "demo6").contains(task)) {
            System.err.println("Cú pháp: java Build.java [build | test | server | client | demo5 | demo6]");
            System.exit(1);
        }
        if (!Files.isDirectory(ROOT.resolve("src/main/java"))) {
            throw new IOException("Hãy chạy từ thư mục gốc chứa src/main/java");
        }

        String libs = libClasspath();
        compile("src/main/java", CLASSES, libs.isEmpty() ? null : libs);
        jar();

        String runCp = CLASSES + (libs.isEmpty() ? "" : File.pathSeparator + libs);

        if (task.equals("test")) {
            compile("src/test/java", TESTS, runCp);
            run(java(), "-ea", "-cp", CLASSES + File.pathSeparator + TESTS + (libs.isEmpty() ? "" : File.pathSeparator + libs), "test.IntegrationTest");
            run(java(), "-ea", "-cp", CLASSES + File.pathSeparator + TESTS + (libs.isEmpty() ? "" : File.pathSeparator + libs), "test.RegressionTest");
            run(java(), "-ea", "-cp", CLASSES + File.pathSeparator + TESTS + (libs.isEmpty() ? "" : File.pathSeparator + libs), "test.UiRegressionTest");
            run(java(), "-cp", runCp, "client.ClientMain", "--smoke-test");
            run(java(), "-cp", runCp, "server.ServerMain", "--smoke-test");
        } else if (task.equals("run") || task.equals("client")) {
            String initialName = args.length > 1 ? args[1] : "";
            if (initialName.isEmpty()) {
                run(java(), "-cp", runCp, "client.ClientMain");
            } else {
                run(java(), "-cp", runCp, "client.ClientMain", initialName);
            }
        } else if (task.equals("server")) {
            var cmd = new ArrayList<>(List.of(java(), "-cp", runCp, "server.ServerMain"));
            for (int i = 1; i < args.length; i++) cmd.add(args[i]);
            run(cmd.toArray(String[]::new));
        } else if (task.startsWith("demo")) {
            int count = task.equals("demo6") ? 6 : 5;
            System.out.println("Đang mở demo: 1 Server danh bạ + " + count + " Clients...");
            new ProcessBuilder(java(), "-cp", runCp, "server.ServerMain").start();
            Thread.sleep(1200);
            String[] demoNames = {"An", "Binh", "Cuong", "Dung", "Giang", "Hoa"};
            for (int i = 0; i < count; i++) {
                new ProcessBuilder(java(), "-cp", runCp, "client.ClientMain", demoNames[i]).start();
                Thread.sleep(350);
            }
            System.out.println("Đã mở thành công 1 Server và " + count + " Clients trên màn hình!");
        }
        System.out.println("OK: " + task + " | " + JAR);
    }

    private static String libClasspath() {
        Path libDir = ROOT.resolve("lib");
        if (!Files.isDirectory(libDir)) return "";
        var sb = new StringBuilder();
        try (var stream = Files.list(libDir)) {
            for (Path p : stream.filter(p -> p.toString().endsWith(".jar")).toList()) {
                if (sb.length() > 0) sb.append(File.pathSeparator);
                sb.append(p.toAbsolutePath().toString());
            }
        } catch (IOException ignored) {}
        return sb.toString();
    }

    private static void compile(String source, Path output, String classpath) throws IOException {
        cleanGenerated(output);
        Files.createDirectories(output);
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IOException("Yêu cầu JDK 21+ (không phải JRE)");
        var arguments = new ArrayList<>(List.of("--release", "21", "-encoding", "UTF-8", "-d", output.toString()));
        if (classpath != null) arguments.addAll(List.of("-classpath", classpath));
        try (var sources = Files.walk(ROOT.resolve(source))) {
            sources.filter(path -> path.toString().endsWith(".java")).sorted().forEach(path -> arguments.add(path.toString()));
        }
        if (compiler.run(null, System.out, System.err, arguments.toArray(String[]::new)) != 0) {
            throw new IOException("Lỗi biên dịch Java (Compilation failed)");
        }
    }

    private static void jar() throws IOException {
        Files.createDirectories(JAR.getParent());
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "client.ClientMain");
        try (var jar = new JarOutputStream(Files.newOutputStream(JAR), manifest); var files = Files.walk(CLASSES)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                jar.putNextEntry(new JarEntry(CLASSES.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, jar);
                jar.closeEntry();
            }
        }
    }

    private static String java() { return tool("java"); }
    private static String tool(String name) {
        return Path.of(System.getProperty("java.home"), "bin", name + (System.getProperty("os.name").startsWith("Windows") ? ".exe" : "")).toString();
    }
    private static void run(String... command) throws Exception {
        int exit = new ProcessBuilder(command).inheritIO().start().waitFor();
        if (exit != 0) throw new IOException("Lệnh thất bại (" + exit + "): " + String.join(" ", command));
    }
    private static void cleanGenerated(Path target) throws IOException {
        target = target.toAbsolutePath().normalize();
        if (!Set.of(CLASSES, TESTS).contains(target))
            throw new IOException("Không thuộc thư mục build: " + target);
        if (Files.exists(target)) {
            try (var paths = Files.walk(target)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    if (!path.equals(target)) Files.delete(path);
                }
            }
        }
    }
}
