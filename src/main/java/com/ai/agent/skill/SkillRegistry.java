package com.ai.agent.skill;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SkillRegistry {
    private static final String SKILL_MARKDOWN = "SKILL.md";
    private final List<SkillPreview> previews;
    private final Map<String, SkillPreview> previewsByName;

    public SkillRegistry(Path skillsRoot) {
        this(skillsRoot, List.of());
    }

    public SkillRegistry(Path skillsRoot, List<String> enabledSkillNames) {
        this.previewsByName = loadPreviews(skillsRoot, enabledSkillNames);
        this.previews = List.copyOf(previewsByName.values());
    }

    public List<SkillPreview> previews() {
        return previews;
    }

    public boolean contains(String skillName) {
        return previewsByName.containsKey(skillName);
    }

    private static Map<String, SkillPreview> loadPreviews(Path skillsRoot, List<String> enabledSkillNames) {
        if (skillsRoot == null) {
            throw new SkillRegistryException(
                    SkillRegistryErrorCode.SKILLS_ROOT_UNAVAILABLE,
                    "skills root is unavailable"
            );
        }
        Path realRoot;
        try {
            realRoot = skillsRoot.toRealPath();
        } catch (IOException ex) {
            throw new SkillRegistryException(
                    SkillRegistryErrorCode.SKILLS_ROOT_UNAVAILABLE,
                    "skills root is unavailable"
            );
        }
        if (!Files.isDirectory(realRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new SkillRegistryException(
                    SkillRegistryErrorCode.SKILLS_ROOT_UNAVAILABLE,
                    "skills root is unavailable"
            );
        }
        List<String> enabled = normalizeEnabledNames(enabledSkillNames);
        try (var stream = Files.list(realRoot)) {
            Map<String, SkillPreview> scanned = stream
                    .filter(SkillRegistry::isRegularDirectory)
                    .map(skillDir -> readPreview(realRoot, skillDir))
                    .sorted(Comparator.comparing(SkillPreview::name))
                    .collect(LinkedHashMap::new, (map, preview) -> map.put(preview.name(), preview), Map::putAll);
            if (enabled.isEmpty()) {
                return Collections.unmodifiableMap(scanned);
            }
            Map<String, SkillPreview> filtered = new LinkedHashMap<>();
            for (String skillName : enabled) {
                SkillPreview preview = scanned.get(skillName);
                if (preview == null) {
                    throw new SkillRegistryException(
                            SkillRegistryErrorCode.SKILL_FILE_READ_FAILED,
                            "enabled skill was not found: " + skillName
                    );
                }
                filtered.put(skillName, preview);
            }
            return Collections.unmodifiableMap(filtered);
        } catch (IOException ex) {
            throw new SkillRegistryException(
                    SkillRegistryErrorCode.SKILL_FILE_READ_FAILED,
                    "failed to scan skills root"
            );
        }
    }

    private static List<String> normalizeEnabledNames(List<String> enabledSkillNames) {
        if (enabledSkillNames == null || enabledSkillNames.isEmpty()) {
            return List.of();
        }
        return enabledSkillNames.stream()
                .map(name -> name == null ? "" : name.trim())
                .peek(name -> {
                    if (!SkillNames.isValid(name)) {
                        throw new SkillRegistryException(
                                SkillRegistryErrorCode.SKILL_FRONTMATTER_INVALID,
                                "enabled skill name is invalid"
                        );
                    }
                })
                .distinct()
                .toList();
    }

    private static boolean isRegularDirectory(Path path) {
        if (Files.isSymbolicLink(path)) {
            throw new SkillRegistryException(
                    SkillRegistryErrorCode.SKILL_PATH_ESCAPE,
                    "skill directory must not be a symbolic link"
            );
        }
        return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
    }

    private static SkillPreview readPreview(Path realRoot, Path skillDir) {
        String directoryName = skillDir.getFileName().toString();
        if (!SkillNames.isValid(directoryName)) {
            throw new SkillRegistryException(
                    SkillRegistryErrorCode.SKILL_FRONTMATTER_INVALID,
                    "skill directory name is invalid"
            );
        }
        Path skillFile = skillDir.resolve(SKILL_MARKDOWN);
        if (Files.isSymbolicLink(skillFile)
                || !Files.isRegularFile(skillFile, LinkOption.NOFOLLOW_LINKS)
                || !skillFile.normalize().startsWith(realRoot)) {
            throw new SkillRegistryException(
                    SkillRegistryErrorCode.SKILL_PATH_ESCAPE,
                    "skill file is outside the skills root"
            );
        }
        String markdown;
        try {
            markdown = Files.readString(skillFile);
        } catch (IOException ex) {
            throw new SkillRegistryException(
                    SkillRegistryErrorCode.SKILL_FILE_READ_FAILED,
                    "failed to read skill metadata"
            );
        }

        Map<String, String> frontmatter = parseFrontmatter(markdown);
        String name = frontmatter.get("name");
        String description = frontmatter.get("description");
        if (name == null || name.isBlank() || description == null || description.isBlank()) {
            throw new SkillRegistryException(
                    SkillRegistryErrorCode.SKILL_FRONTMATTER_INVALID,
                    "skill frontmatter must include name and description"
            );
        }
        String normalizedName = name.trim();
        if (!SkillNames.isValid(normalizedName) || !directoryName.equals(normalizedName)) {
            throw new SkillRegistryException(
                    SkillRegistryErrorCode.SKILL_FRONTMATTER_INVALID,
                    "skill frontmatter name must match the skill directory"
            );
        }
        return new SkillPreview(normalizedName, description.trim());
    }

    private static Map<String, String> parseFrontmatter(String markdown) {
        List<String> lines = markdown.lines().toList();
        if (lines.isEmpty() || !lines.getFirst().trim().equals("---")) {
            throw new SkillRegistryException(
                    SkillRegistryErrorCode.SKILL_FRONTMATTER_MISSING,
                    "skill frontmatter is missing"
            );
        }

        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 1; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.trim().equals("---")) {
                return values;
            }
            int separator = line.indexOf(':');
            if (separator > 0) {
                values.put(line.substring(0, separator).trim(), line.substring(separator + 1).trim());
            }
        }
        throw new SkillRegistryException(
                SkillRegistryErrorCode.SKILL_FRONTMATTER_MISSING,
                "skill frontmatter is missing"
        );
    }
}
