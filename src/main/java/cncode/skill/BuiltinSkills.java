package cncode.skill;

import java.util.List;

public final class BuiltinSkills {
    private BuiltinSkills() {
    }

    public static List<SkillDefinition> load() {
        SkillParser parser = new SkillParser();
        return List.of(
                parser.parseBuiltin("commit", """
                        ---
                        name: commit
                        description: Analyze changes and create a careful commit
                        allowedTools: [read_file, search_code, find_files, run_command]
                        mode: inline
                        ---
                        Review the current git diff, identify the intended change, run a focused verification when practical, then propose and create a concise commit.
                        Do not commit unrelated user changes.
                        """),
                parser.parseBuiltin("review", """
                        ---
                        name: review
                        description: Review current changes for correctness and risk
                        allowedTools: [read_file, search_code, find_files, run_command]
                        mode: inline
                        ---
                        Review the current changes. Focus on logic errors, security issues, performance problems, missing tests, and code style.
                        Report findings first with file and line references when available.
                        """),
                parser.parseBuiltin("test", """
                        ---
                        name: test
                        description: Find and run the most relevant project tests
                        allowedTools: [read_file, search_code, find_files, run_command]
                        mode: fork
                        forkContext: recent
                        ---
                        Identify the project's test command, run the smallest relevant verification first, then summarize what passed, failed, and what remains risky.
                        """)
        );
    }
}
