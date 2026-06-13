package cncode.prompt;

public record PromptEnvironment(
        String workDir,
        String os,
        String shell,
        String dateTime,
        boolean gitRepo,
        String gitBranch,
        String gitStatus
) {
    public PromptEnvironment {
        workDir = workDir == null ? "" : workDir;
        os = os == null ? "" : os;
        shell = shell == null ? "" : shell;
        dateTime = dateTime == null ? "" : dateTime;
        gitBranch = gitBranch == null ? "" : gitBranch;
        gitStatus = gitStatus == null ? "" : gitStatus;
    }
}
