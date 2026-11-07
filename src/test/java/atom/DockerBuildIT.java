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

    private AtomResult run(String input) throws Exception {
        return atom.input(input)
                .input("dockerBinary", binary.toString())
                .env("SMOKE_ARGV", argv.toString())
                .env("SMOKE_STDIN", stdin.toString())
                .run();
    }
}
