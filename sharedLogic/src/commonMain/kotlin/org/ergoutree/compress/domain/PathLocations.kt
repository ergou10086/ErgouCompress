package org.ergoutree.compress.domain

/** 比较已规范化绝对路径的位置，兼容历史 Windows 路径中的混合分隔符。 */
fun LocalPath.sameLocation(other: LocalPath): Boolean {
    val left = value.replace('\\', '/')
    val right = other.value.replace('\\', '/')
    // Windows 路径引用大小写不敏感；实际文件身份仍必须另行核对。
    val windows = left.startsWith("//") || left.length > 2 && left[1] == ':'
    return left.equals(right, ignoreCase = windows)
}
