package io.patchradar.collector.config;

import java.util.List;

/** contracts/domain.md 도구 표. location: github는 owner/repo, rss는 피드 URL. */
public record Sources(String tool, String type, String location) {

    public static final List<Sources> ALL = List.of(
            new Sources("gitlab", "rss", "https://about.gitlab.com/all-releases.xml"),
            new Sources("gitea", "github", "go-gitea/gitea"),
            new Sources("jenkins", "rss", "https://www.jenkins.io/changelog-stable/rss.xml"),
            new Sources("sonarqube", "github", "SonarSource/sonarqube"),
            new Sources("harbor", "github", "goharbor/harbor"),
            new Sources("argocd", "github", "argoproj/argo-cd"),
            new Sources("dependency-track", "github", "DependencyTrack/dependency-track"),
            new Sources("k3s", "github", "k3s-io/k3s"),
            new Sources("kubernetes", "github", "kubernetes/kubernetes"));
}
