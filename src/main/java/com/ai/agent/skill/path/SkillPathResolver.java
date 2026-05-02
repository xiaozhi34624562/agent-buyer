package com.ai.agent.skill.path;

import com.ai.agent.skill.core.SkillNames;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * 技能路径解析器。
 * 安全地解析技能文件路径，防止路径越界攻击。
 */
public class SkillPathResolver {
    /** 默认技能文件名 */
    private static final String DEFAULT_SKILL_PATH = "SKILL.md";
    private final Path skillsRoot;

    /**
     * 构造技能路径解析器。
     *
     * @param skillsRoot 技能根目录路径
     */
    public SkillPathResolver(Path skillsRoot) {
        this.skillsRoot = skillsRoot;
    }

    /**
     * 查看技能文件内容。
     *
     * @param skillName 技能名称
     * @param skillPath 技能内文件路径，为空时默认读取SKILL.md
     * @return 文件内容
     * @throws SkillPathException 如果路径无效或越界
     */
    public String view(String skillName, String skillPath) {
        Path root = realSkillsRoot();
        Path skillRoot = realSkillRoot(root, skillName);
        Path requested = requestedPath(skillPath);
        Path candidate = skillRoot.resolve(requested).normalize();
        if (!candidate.startsWith(skillRoot)) {
            throw escape();
        }

        Path realCandidate;
        try {
            realCandidate = candidate.toRealPath();
        } catch (IOException ex) {
            throw new SkillPathException(SkillPathErrorCode.FILE_NOT_FOUND, "skill file was not found");
        }
        if (!realCandidate.startsWith(skillRoot)) {
            throw escape();
        }

        try {
            return Files.readString(realCandidate);
        } catch (IOException ex) {
            throw new SkillPathException(SkillPathErrorCode.FILE_READ_FAILED, "failed to read skill file");
        }
    }

    private Path realSkillsRoot() {
        if (skillsRoot == null) {
            throw new SkillPathException(SkillPathErrorCode.SKILLS_ROOT_UNAVAILABLE, "skills root is unavailable");
        }
        try {
            return skillsRoot.toRealPath();
        } catch (IOException ex) {
            throw new SkillPathException(SkillPathErrorCode.SKILLS_ROOT_UNAVAILABLE, "skills root is unavailable");
        }
    }

    private static Path realSkillRoot(Path root, String skillName) {
        if (skillName == null || skillName.isBlank()) {
            throw new SkillPathException(SkillPathErrorCode.SKILL_NOT_FOUND, "skill was not found");
        }
        if (!SkillNames.isValid(skillName)) {
            throw new SkillPathException(SkillPathErrorCode.INVALID_SKILL_NAME, "skill name is invalid");
        }
        Path skillPath = Path.of(skillName);
        if (skillPath.isAbsolute() || hasParentTraversal(skillPath)) {
            throw escape();
        }
        Path skillRoot = root.resolve(skillPath).normalize();
        if (!skillRoot.startsWith(root)) {
            throw escape();
        }
        try {
            Path realSkillRoot = skillRoot.toRealPath();
            if (!realSkillRoot.startsWith(root)) {
                throw escape();
            }
            return realSkillRoot;
        } catch (IOException ex) {
            throw new SkillPathException(SkillPathErrorCode.SKILL_NOT_FOUND, "skill was not found");
        }
    }

    private static Path requestedPath(String skillPath) {
        String value = skillPath == null || skillPath.isBlank() ? DEFAULT_SKILL_PATH : skillPath;
        Path requested;
        try {
            requested = Path.of(value);
        } catch (InvalidPathException ex) {
            throw escape();
        }
        if (requested.isAbsolute() || hasParentTraversal(requested)) {
            throw escape();
        }
        return requested;
    }

    private static boolean hasParentTraversal(Path path) {
        for (Path segment : path) {
            if (segment.toString().equals("..")) {
                return true;
            }
        }
        return false;
    }

    private static SkillPathException escape() {
        return new SkillPathException(SkillPathErrorCode.PATH_ESCAPE, "skill path is outside the skill directory");
    }
}
