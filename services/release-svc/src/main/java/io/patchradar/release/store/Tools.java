package io.patchradar.release.store;

import java.util.List;

/** contracts/domain.md 도구 표와 일치시킨다. */
public final class Tools {
    public record Info(String id, String name, String category) {}

    public static final List<Info> ALL = List.of(
            new Info("gitlab", "GitLab", "SCM"),
            new Info("gitea", "Gitea", "SCM"),
            new Info("jenkins", "Jenkins LTS", "CI"),
            new Info("sonarqube", "SonarQube", "품질 분석"),
            new Info("harbor", "Harbor", "레지스트리"),
            new Info("argocd", "ArgoCD", "배포"),
            new Info("dependency-track", "Dependency-Track", "보안"),
            new Info("k3s", "k3s", "인프라"),
            new Info("kubernetes", "Kubernetes", "인프라"));

    public static boolean exists(String id) {
        return ALL.stream().anyMatch(t -> t.id().equals(id));
    }

    private Tools() {}
}
