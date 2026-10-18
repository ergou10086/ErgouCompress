package org.ergoutree.compress.domain

/** Platform UI/CLI may render these descriptors; no UI dependency belongs in this contract. */
data class ParameterDescriptor(val id: String, val explanation: String)
object ParameterHelp {
    val settings = listOf(
        ParameterDescriptor("backend", "x264 使用 CPU，NVENC 使用经过实际自检的 NVIDIA HEVC 编码器；不会隐式切换后端"),
        ParameterDescriptor("quality", "质量值 0–51，越低通常越清晰和越大；CRF/CQ 不等价，不保证文件大小"),
        ParameterDescriptor("speed", "速度档影响耗时和压缩效率，不代替质量设置"),
        ParameterDescriptor("container", "目标 MP4/MKV 必须兼容所有保留流；更换容器不等于重新编码"),
        ParameterDescriptor("operation", "ENCODE 重新编码主视频；REMUX 只允许 Copy/Drop，不接受滤镜或码率覆盖"),
        ParameterDescriptor("streams", "按源流绝对索引指定 Copy/Encode/Drop；未指定的非视频流默认复制，不能静默丢轨；丢弃默认轨后，MP4 必须为保留轨明确选择 defaultDisposition"),
        ParameterDescriptor("maxWidth", "可选最大偶数宽度；保持显示宽高比、禁止放大，不是强制拉伸尺寸"),
        ParameterDescriptor("maxHeight", "可选最大偶数高度；与宽度共同限定缩放框"),
        ParameterDescriptor("frameRate", "默认保留时间线和帧率；显式有理数表示 CFR 转换，会复制或丢弃视频帧"),
        ParameterDescriptor("rateControl", "质量模式与平均码率预算互斥；平均码率也不是硬性字节上限"),
        ParameterDescriptor("videoBitrateKbps", "仅码率模式需要，单位为十进制 kbit/s；音频码率另计"),
        ParameterDescriptor("preserveMetadata", "默认保留用户文件/流标签；关闭会丢失标题等标签，disposition 仍按计划保留"),
        ParameterDescriptor("preserveChapters", "默认保留章节；关闭是明确的内容删除决定"),
    )
    val aac = listOf(
        ParameterDescriptor("bitrateKbps", "AAC 目标平均码率；重编码有损，复制音频更适合无需改变的音轨"),
        ParameterDescriptor("sampleRate", "留空保持源采样率；指定值才重采样，不会自动升到 48 kHz"),
        ParameterDescriptor("channels", "留空保持源声道和布局；明确指定较小值才允许降混"),
    )
}
