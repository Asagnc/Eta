package io.github.mangi.eta.agent.terminal

/** 一组 apt 源；按顺序尝试，成功者写回 sources.list。 */
internal data class AptMirror(
    val id: String,
    val sources: List<String>,
)

/** apt 系发行版（Debian / Ubuntu / Kali）的版本、软件源与 rootfs 制品。 */
internal object AptDistributionSpecs {
    fun versionOf(distribution: LinuxDistribution): String = when (distribution) {
        LinuxDistribution.DEBIAN -> "13"
        LinuxDistribution.UBUNTU -> "25.04"
        LinuxDistribution.KALI -> "2026.2"
    }

    /** 真机链路只保留一个国内镜像和官方源，避免慢镜像串行拖长安装。 */
    fun mirrorsOf(distribution: LinuxDistribution): List<AptMirror> = when (distribution) {
        LinuxDistribution.DEBIAN -> listOf(
            AptMirror(
                id = "tuna",
                sources = listOf(
                    "deb https://mirrors.tuna.tsinghua.edu.cn/debian trixie main",
                    "deb https://mirrors.tuna.tsinghua.edu.cn/debian trixie-updates main",
                    "deb https://security.debian.org/debian-security trixie-security main",
                ),
            ),
            AptMirror(
                id = "official",
                sources = listOf(
                    "deb https://deb.debian.org/debian trixie main",
                    "deb https://deb.debian.org/debian trixie-updates main",
                    "deb https://security.debian.org/debian-security trixie-security main",
                ),
            ),
        )
        LinuxDistribution.UBUNTU -> listOf(
            AptMirror(
                id = "tuna",
                sources = listOf(
                    "deb https://mirrors.tuna.tsinghua.edu.cn/ubuntu plucky main restricted universe multiverse",
                    "deb https://mirrors.tuna.tsinghua.edu.cn/ubuntu plucky-updates main restricted universe multiverse",
                    "deb https://mirrors.tuna.tsinghua.edu.cn/ubuntu plucky-security main restricted universe multiverse",
                ),
            ),
            AptMirror(
                id = "official",
                sources = listOf(
                    "deb http://archive.ubuntu.com/ubuntu plucky main restricted universe multiverse",
                    "deb http://archive.ubuntu.com/ubuntu plucky-updates main restricted universe multiverse",
                    "deb http://security.ubuntu.com/ubuntu plucky-security main restricted universe multiverse",
                ),
            ),
        )
        LinuxDistribution.KALI -> listOf(
            AptMirror(
                id = "tuna",
                sources = listOf(
                    "deb https://mirrors.tuna.tsinghua.edu.cn/kali kali-rolling main contrib non-free non-free-firmware",
                ),
            ),
            AptMirror(
                id = "official",
                sources = listOf(
                    "deb http://http.kali.org/kali kali-rolling main contrib non-free non-free-firmware",
                ),
            ),
        )
    }

    fun artifactOf(distribution: LinuxDistribution, abi: String): VerifiedArtifact? = when (distribution) {
        LinuxDistribution.DEBIAN -> when (abi) {
            "arm64-v8a" -> prootDistroArtifact(
                id = "debian-trixie-aarch64-pd-v4.29.0",
                fileName = "debian-trixie-aarch64-pd-v4.29.0.tar.xz",
                sha256 = "3834a11cbc6496935760bdc20cca7e2c25724d0cd8f5e4926da8fd5ca1857918",
                sizeBytes = 35_409_704L,
                version = versionOf(distribution),
            )
            "x86_64" -> prootDistroArtifact(
                id = "debian-trixie-x86_64-pd-v4.29.0",
                fileName = "debian-trixie-x86_64-pd-v4.29.0.tar.xz",
                sha256 = "4b8f33b80a10d734ff935e5934588572f860c0c38a68bf91db59af0580370716",
                sizeBytes = 36_728_936L,
                version = versionOf(distribution),
            )
            else -> null
        }
        LinuxDistribution.UBUNTU -> when (abi) {
            "arm64-v8a" -> prootDistroArtifact(
                id = "ubuntu-plucky-aarch64-pd-v4.29.0",
                fileName = "ubuntu-plucky-aarch64-pd-v4.29.0.tar.xz",
                sha256 = "63cee3aecc0473785ef761ec1127387ed2abbea0b26d74e5187601568fbb335f",
                sizeBytes = 56_752_204L,
                version = versionOf(distribution),
            )
            "x86_64" -> prootDistroArtifact(
                id = "ubuntu-plucky-x86_64-pd-v4.29.0",
                fileName = "ubuntu-plucky-x86_64-pd-v4.29.0.tar.xz",
                sha256 = "fcac0b71a98524e1dd10a3b1fe6753b8e85716b98207940169fe01bbd21b1538",
                sizeBytes = 61_294_804L,
                version = versionOf(distribution),
            )
            else -> null
        }
        LinuxDistribution.KALI -> when (abi) {
            "arm64-v8a" -> kaliArtifact(
                id = "kali-nethunter-2026.2-minimal-arm64",
                fileName = "kali-nethunter-rootfs-minimal-arm64.tar.xz",
                sha256 = "d6403a5da175df325611d23af4b92330856059c45454eced7f4cdf3ca6df2e4e",
                sizeBytes = 137_313_840L,
                version = versionOf(distribution),
            )
            "x86_64" -> kaliArtifact(
                id = "kali-nethunter-2026.2-minimal-amd64",
                fileName = "kali-nethunter-rootfs-minimal-amd64.tar.xz",
                sha256 = "4c0847c6409be9b65c9fe3bb7a8a6af9d265ab56837323e2e319bb733f1ed86a",
                sizeBytes = 144_746_948L,
                version = versionOf(distribution),
            )
            else -> null
        }
    }

    private fun prootDistroArtifact(
        id: String,
        fileName: String,
        sha256: String,
        sizeBytes: Long,
        version: String,
    ): VerifiedArtifact {
        val officialUrl = "https://github.com/termux/proot-distro/releases/download/v4.29.0/$fileName"
        return VerifiedArtifact(
            id = id,
            version = version,
            fileName = fileName,
            url = officialUrl,
            sha256 = sha256,
            sizeBytes = sizeBytes,
            preferredUrls = GITHUB_PROXY_PREFIXES.map { prefix -> prefix + officialUrl },
        )
    }

    /** Kali 的 rootfs 由官方 NetHunter 项目发布；镜像目录按版本固定，保证校验值稳定。 */
    private fun kaliArtifact(
        id: String,
        fileName: String,
        sha256: String,
        sizeBytes: Long,
        version: String,
    ): VerifiedArtifact = VerifiedArtifact(
        id = id,
        version = version,
        fileName = fileName,
        url = "https://kali.download/nethunter-images/kali-$version/rootfs/$fileName",
        sha256 = sha256,
        sizeBytes = sizeBytes,
        preferredUrls = emptyList(),
    )

    private val GITHUB_PROXY_PREFIXES = listOf(
        "https://gh-proxy.com/",
    )
}
