package io.nekohasekai.sagernet.update

/** Compares machine-readable tags, never translated release titles. */
data class ReleaseVersion(val numbers: List<Long>, val preview: List<String>) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int {
        numbers.zip(other.numbers).forEach { (a,b) -> if (a != b) return a.compareTo(b) }
        if (preview.isEmpty() && other.preview.isNotEmpty()) return 1
        if (preview.isNotEmpty() && other.preview.isEmpty()) return -1
        preview.zip(other.preview).forEach { (a,b) ->
            val an = a.toLongOrNull(); val bn = b.toLongOrNull()
            val cmp = when { an != null && bn != null -> an.compareTo(bn); an != null -> -1; bn != null -> 1; else -> a.compareTo(b) }
            if (cmp != 0) return cmp
        }
        return preview.size.compareTo(other.preview.size)
    }
    companion object {
        private val pattern = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-arcaenbox\\.(\\d+))?(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$")
        fun parse(tag: String): ReleaseVersion? {
            val m = pattern.matchEntire(tag) ?: return null
            val numbers = (1..4).map { if (m.groupValues[it].isEmpty()) 0L else m.groupValues[it].toLongOrNull() ?: return null }
            val preview = m.groupValues[5].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList()
            return ReleaseVersion(numbers, preview)
        }
    }
}
