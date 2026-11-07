package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The docker-build atom, started the way the runner starts it.
 *
 * <p>These are the checks the old {@code tools/smoke.sh} made — a fake docker CLI records the argv: images, tags,
 * build args, that a password never appears in argv and does arrive on stdin, and that a missing CLI is an
 * environment error — written as JUnit.
 */
class DockerBuildIT {

    /** What the runner's agent reports; the fake answers in the shape the atom parses. */
    private static final String DOCKER = """
            #!/bin/sh
            printf '%s\\n' "$*" >> "$SMOKE_ARGV"
            case "$1" in
              version) echo "27.0.3" ;;
              build)   echo "Successfully built sha256:abc123" ;;
              image)   echo "sha256:abc123def456" ;;
              login)   cat > "$SMOKE_STDIN" ;;
              push)    echo "pushed $2" ;;
            esac
            exit 0
            """;

    private Atom atom;
    private Path binary;
    private Path argv;
    private Path stdin;

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
        Path bin = Files.createDirectories(Path.of(System.getProperty("atom.scratch")).resolve("fake-docker/bin"));
        binary = bin.resolve("docker");
        Files.writeString(binary, DOCKER, StandardCharsets.UTF_8);
        binary.toFile().setExecutable(true);
        argv = bin.resolve("argv.txt");
        stdin = bin.resolve("stdin.txt");
        Files.deleteIfExists(argv);
        Files.deleteIfExists(stdin);
    }

    @Test
    @DisplayName("two tags and a build arg reach the CLI, and push=false does not push")
    void buildsWithTagsAndBuildArgs() throws Exception {
        AtomResult run = run("{\"image\": \"registry.example.com/parcel\", \"tags\": \"1.4.0, latest\","
                + " \"buildArgs\": \"VERSION=1.4.0\", \"context\": \".\"}");

        run.assertOk();
        assertEquals("success", run.status());
        assertEquals("sha256:abc123def456", run.field("imageId"));
        assertEquals("registry.example.com/parcel:1.4.0,registry.example.com/parcel:latest", run.field("tags"));

        String commands = Files.readString(argv, StandardCharsets.UTF_8);
        assertTrue(commands.contains("--tag registry.example.com/parcel:1.4.0"), "first tag missing:\n" + commands);
        assertTrue(commands.contains("--tag registry.example.com/parcel:latest"), "second tag missing:\n" + commands);
        assertTrue(commands.contains("--build-arg VERSION=1.4.0"), "build arg missing:\n" + commands);
        assertFalse(commands.contains("push"), "push=false must not push:\n" + commands);
    }

    @Test
    @DisplayName("push logs in, sends the password on stdin, and keeps it out of argv and the outputs")
    void pushesWithoutLeakingThePassword() throws Exception {
        AtomResult run = atom.file(".secrets.json", "{\"registryUser\": \"robot\", \"registryPassword\": \"hunter2\"}")
                .input("{\"image\": \"registry.example.com/parcel\", \"tags\": \"latest\", \"push\": \"true\","
                        + " \"dockerBinary\": \"" + binary + "\"}")
                .env("SMOKE_ARGV", argv.toString())
                .env("SMOKE_STDIN", stdin.toString())
                .run();

        run.assertOk();
        String commands = Files.readString(argv, StandardCharsets.UTF_8);
        assertTrue(commands.contains("push registry.example.com/parcel:latest"), "push missing:\n" + commands);
        assertTrue(commands.contains("login registry.example.com"), "login missing:\n" + commands);
        assertFalse(commands.contains("hunter2"), "the password must not be in argv:\n" + commands);
        assertTrue(Files.readString(stdin, StandardCharsets.UTF_8).contains("hunter2"),
                "the password must reach docker through stdin");
        assertFalse(run.file("output.json").contains("hunter2"), "the password must not be in the outputs");
    }

    @Test
    @DisplayName("a docker CLI that is not there is a clear environment error")
    void refusesWithoutTheCli() throws Exception {
        Path missing = Path.of(System.getProperty("atom.scratch")).resolve("no-such-docker");
        AtomResult run = atom.input("{\"image\": \"x/y\", \"dockerBinary\": \"" + missing + "\"}").run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("docker CLI is not available"),
                "message: " + run.file("output.json"));
    }

    @Test
    @DisplayName("an image name is required before the CLI is even looked up")
    void refusesWithoutAnImage() throws Exception {
        AtomResult run = atom.input("{}").input("dockerBinary", binary.toString()).run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("image is required"),
                "message: " + run.file("output.json"));
    }

    @Test
    @DisplayName("a CLI whose version check fails is the same environment error as a missing CLI")
    void refusesWhenTheDaemonIsUnreachable() throws Exception {
        Path broken = Files.createDirectories(Path.of(System.getProperty("atom.scratch")).resolve("broken-docker"))
                .resolve("docker");
        Files.writeString(broken, "#!/bin/sh\necho 'Cannot connect to the Docker daemon'\nexit 1\n",
                StandardCharsets.UTF_8);
        broken.toFile().setExecutable(true);

        AtomResult run = atom.input("{\"image\": \"x/y\", \"dockerBinary\": \"" + broken + "\"}").run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("docker CLI is not available"),
                "message: " + run.file("output.json"));
    }

    @Test
    @DisplayName("a binary that resolves but cannot be executed is an environment error")
    void refusesWhenTheBinaryCannotRun() throws Exception {
        Path directory = Files.createDirectories(Path.of(System.getProperty("atom.scratch")).resolve("not-a-binary"));

        AtomResult run = atom.input("{\"image\": \"x/y\", \"dockerBinary\": \"" + directory + "\"}").run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("docker CLI is not available"),
                "message: " + run.file("output.json"));
    }

    @Test
    @DisplayName("platform, extra args and every tag reach the build command")
    void passesPlatformAndExtraArgs() throws Exception {
        AtomResult run = run("{\"image\": \"registry.example.com/parcel\", \"tags\": \"1.4.0,1.4,latest\","
                + " \"platform\": \"linux/arm64\", \"extraArgs\": \"--pull --no-cache\", \"context\": \".\"}");

        run.assertOk();
        String commands = Files.readString(argv, StandardCharsets.UTF_8);
        assertTrue(commands.contains("--platform linux/arm64"), "platform missing:\n" + commands);
        assertTrue(commands.contains("--pull --no-cache"), "extra args missing:\n" + commands);
        assertTrue(commands.contains("--tag registry.example.com/parcel:1.4.0"), "tag 1 missing:\n" + commands);
        assertTrue(commands.contains("--tag registry.example.com/parcel:1.4"), "tag 2 missing:\n" + commands);
        assertEquals("registry.example.com/parcel:1.4.0,registry.example.com/parcel:1.4,"
                + "registry.example.com/parcel:latest", run.field("tags"));
    }

    @Test
    @DisplayName("a build that fails reports the exit code and the tail of docker's output")
    void reportsAFailedBuild() throws Exception {
        Path failing = failing("build", 3, "step 1: FROM alpine\\nERROR: failed to solve: not found");

        AtomResult run = atom.input("{\"image\": \"x/y\", \"dockerBinary\": \"" + failing + "\"}").run();

        run.assertRefused();
        String output = run.file("output.json");
        assertTrue(output.contains("docker build exited 3"), "exit code missing: " + output);
        assertTrue(output.contains("failed to solve: not found"), "docker tail missing: " + output);
    }

    @Test
    @DisplayName("a failed registry login stops the run before anything is pushed")
    void reportsAFailedLogin() throws Exception {
        Path failing = failing("login", 1, "Error response from daemon: unauthorized");

        AtomResult run = atom.file(".secrets.json",
                        "{\"registryUser\": \"robot\", \"registryPassword\": \"hunter2\"}")
                .input("{\"image\": \"registry.example.com/parcel\", \"push\": \"true\","
                        + " \"dockerBinary\": \"" + failing + "\"}")
                .env("SMOKE_ARGV", argv.toString())
                .run();

        run.assertRefused();
        String output = run.file("output.json");
        assertTrue(output.contains("docker login to registry.example.com failed"), "message: " + output);
        assertTrue(output.contains("unauthorized"), "login tail missing: " + output);
        assertFalse(Files.readString(argv, StandardCharsets.UTF_8).contains("push"),
                "push ran although the login failed");
    }

    @Test
    @DisplayName("a failed push names the tag")
    void reportsAFailedPush() throws Exception {
        Path failing = failing("push", 1, "denied: requested access to the resource is denied");

        AtomResult run = atom.input("{\"image\": \"registry.example.com/parcel\", \"tags\": \"1.0\","
                        + " \"push\": \"true\", \"dockerBinary\": \"" + failing + "\"}")
                .env("SMOKE_ARGV", argv.toString())
                .run();

        run.assertRefused();
        String output = run.file("output.json");
        assertTrue(output.contains("docker push registry.example.com/parcel:1.0 failed"), "message: " + output);
        assertTrue(output.contains("requested access"), "push tail missing: " + output);
    }

    @Test
    @DisplayName("a bare 'docker' is resolved through PATH, and a PATH without it is an environment error")
    void resolvesTheBinaryThroughThePath() throws Exception {
        Path bin = Path.of(System.getProperty("atom.scratch")).resolve("fake-docker/bin");

        AtomResult found = atom.input("{\"image\": \"x/y\", \"dockerBinary\": \"docker\"}")
                .env("SMOKE_ARGV", argv.toString())
                .env("PATH", ":" + bin + ":/usr/bin")
                .run();
        found.assertOk();
        assertEquals("sha256:abc123def456", found.field("imageId"));

        AtomResult missing = atom.input("{\"image\": \"x/y\", \"dockerBinary\": \"docker\"}")
                .env("PATH", "/nonexistent")
                .run();
        missing.assertRefused();
        assertTrue(missing.file("output.json").contains("docker CLI is not available"),
                "message: " + missing.file("output.json"));
    }

    /** A fake docker whose {@code verb} fails, so the error path is the thing under test. */
    private Path failing(String verb, int code, String message) throws Exception {
        Path dir = Files.createDirectories(Path.of(System.getProperty("atom.scratch"))
                .resolve("failing-" + verb));
        Path script = dir.resolve("docker");
        Files.writeString(script, "#!/bin/sh\nprintf '%s\\n' \"$*\" >> \"$SMOKE_ARGV\"\n"
                + "case \"$1\" in\n"
                + "  version) echo 27.0.3 ;;\n"
                + "  build) echo 'Successfully built sha256:abc123' ;;\n"
                + "  image) echo sha256:abc123def456 ;;\n"
                + "  login) cat > /dev/null ;;\n"
                + "esac\n"
                + "if [ \"$1\" = \"" + verb + "\" ]; then printf '" + message + "\\n'; exit " + code + "; fi\n"
                + "exit 0\n", StandardCharsets.UTF_8);
        script.toFile().setExecutable(true);
        Files.deleteIfExists(argv);
        return script;
    }

    private AtomResult run(String input) throws Exception {
        return atom.input(input)
                .input("dockerBinary", binary.toString())
                .env("SMOKE_ARGV", argv.toString())
                .env("SMOKE_STDIN", stdin.toString())
                .run();
    }
}
