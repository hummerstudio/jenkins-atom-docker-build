package com.jenkinschina.atom.dockerbuild;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * docker-build as an atom: build an image with the CLI, tag it, optionally push it.
 *
 * <p>Not docker-workflow: an atom is one process, so it cannot wrap "a stage that runs inside a container". It can
 * however build, tag and push, which covers the build-and-publish half of that plugin.
 */
public class DockerBuildAtom implements AtomTask {

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        Path workspace = context.getWorkspace();
        String image = context.getParam("image", "");
        if (image.isBlank()) {
            return AtomResult.error(ErrorType.USER, 40001, "image is required (e.g. registry.example.com/app)");
        }
        String dockerfile = context.getParam("dockerfile", "Dockerfile");
        String buildContext = context.getParam("context", ".");
        String platform = context.getParam("platform", "");
        String extraArgs = context.getParam("extraArgs", "");
        boolean push = context.getBooleanParam("push", false);
        List<String> tags = split(context.getParam("tags", "latest"));

        // resolve the binary ourselves: the JVM looks a bare name up in the environment it inherited, which on some
        // platforms ignores what the caller put in PATH — the same trap that made the node atom pick the wrong node
        String docker = resolveBinary(context.getParam("dockerBinary", "docker"));
        if (docker == null) {
            return AtomResult.error(ErrorType.ENVIRONMENT, 40002,
                    "the docker CLI is not available or the daemon is not reachable on this agent");
        }
        if (!exec(workspace, context, docker, "version", "--format", "{{.Server.Version}}").ok()) {
            return AtomResult.error(ErrorType.ENVIRONMENT, 40002,
                    "the docker CLI is not available or the daemon is not reachable on this agent");
        }

        List<String> wanted = new ArrayList<>();
        wanted.add(image + ":" + (tags.isEmpty() ? "latest" : tags.get(0)));
        for (int i = 1; i < tags.size(); i++) {
            wanted.add(image + ":" + tags.get(i));
        }

        List<String> command = new ArrayList<>(List.of(docker, "build", "--file", dockerfile));
        for (String tag : wanted) {
            command.add("--tag");
            command.add(tag);
        }
        if (!platform.isBlank()) {
            command.add("--platform");
            command.add(platform);
        }
        for (String arg : split(context.getParam("buildArgs", ""))) {
            command.add("--build-arg");
            command.add(arg);
        }
        for (String arg : split(extraArgs)) {
            command.add(arg);
        }
        command.add(buildContext);

        context.log("[docker] " + String.join(" ", command));
        Result build = exec(workspace, context, command.toArray(new String[0]));
        if (!build.ok()) {
            return AtomResult.error(ErrorType.THIRD_PART, 40003,
                    "docker build exited " + build.exitCode() + ": " + build.tail());
        }

        String imageId = exec(workspace, context, docker, "image", "inspect", "--format", "{{.Id}}",
                wanted.get(0)).output().strip();
        AtomResult result = AtomResult.success()
                .data("imageId", imageId)
                .data("image", wanted.get(0))
                .data("tags", String.join(",", wanted));

        if (!push) {
            return result;
        }

        String registry = image.contains("/") ? image.substring(0, image.indexOf('/')) : "";
        String user = context.getSecret("registryUser");
        String password = context.getSecret("registryPassword");
        if (user != null && !user.isBlank() && password != null && !password.isBlank() && !registry.isBlank()) {
            // --password-stdin so the credential never appears in argv
            Result login = execWithStdin(workspace, context, password, docker, "login", registry,
                    "--username", user, "--password-stdin");
            if (!login.ok()) {
                return AtomResult.error(ErrorType.THIRD_PART, 40004, "docker login to " + registry + " failed: "
                        + login.tail());
            }
        }

        for (String tag : wanted) {
            Result pushed = exec(workspace, context, docker, "push", tag);
            if (!pushed.ok()) {
                return AtomResult.error(ErrorType.THIRD_PART, 40005,
                        "docker push " + tag + " failed: " + pushed.tail());
            }
        }
        return result.data("pushed", "true").data("count", String.valueOf(wanted.size()));
    }

    /** Absolute path for a bare command name, or null when it is nowhere on PATH. */
    private static String resolveBinary(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        if (name.contains(java.io.File.separator)) {
            return java.nio.file.Files.isExecutable(java.nio.file.Path.of(name)) ? name : null;
        }
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String entry : path.split(java.io.File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            java.nio.file.Path candidate = java.nio.file.Path.of(entry).resolve(name);
            if (java.nio.file.Files.isExecutable(candidate)) {
                return candidate.toAbsolutePath().toString();
            }
        }
        return null;
    }

    private static List<String> split(String spec) {
        List<String> parts = new ArrayList<>();
        if (spec == null) {
            return parts;
        }
        for (String part : spec.split("[,\\s]+")) {
            if (!part.isBlank()) {
                parts.add(part.trim());
            }
        }
        return parts;
    }

    private static Result exec(Path workspace, AtomContext context, String... command) {
        return execWithStdin(workspace, context, null, command);
    }

    /** {@code stdin} is only used for the password pipe; everything else runs with an empty stdin. */
    private static Result execWithStdin(Path workspace, AtomContext context, String stdin, String... command) {
        try {
            ProcessBuilder builder = new ProcessBuilder(List.of(command))
                    .directory(workspace.toFile())
                    .redirectErrorStream(true);
            Process process = builder.start();
            if (stdin != null) {
                try (var writer = process.getOutputStream()) {
                    writer.write((stdin + "\n").getBytes(StandardCharsets.UTF_8));
                }
            } else {
                process.getOutputStream().close();
            }
            StringBuilder all = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("{\"stream\"") || line.length() < 400) {
                        context.log("[docker] " + line);
                    }
                    all.append(line).append('\n');
                    if (all.length() > 100_000) {
                        all.delete(0, 50_000);
                    }
                }
            }
            int code = process.waitFor();
            return new Result(code, all.toString());
        } catch (Exception e) {
            context.log("[docker] could not run " + command[0] + ": " + e);
            return new Result(-1, String.valueOf(e));
        }
    }

    private record Result(int exitCode, String output) {
        boolean ok() {
            return exitCode == 0;
        }

        String tail() {
            String[] lines = output.strip().split("\n");
            StringBuilder out = new StringBuilder();
            for (int i = Math.max(0, lines.length - 6); i < lines.length; i++) {
                out.append(lines[i]).append('\n');
            }
            return out.toString().strip();
        }
    }
}
