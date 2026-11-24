package com.gfs.iyango.core.domain

/**
 * 壁纸裁剪页的纯几何计算（逐函数移植自鸿蒙参考实现 `utils/CropGeometry.ets`）。
 *
 * 坐标约定：一套预览矩形空间（裁剪页内图片实际渲染的区域，Contain 居中），
 * 一套解码像素空间；裁剪框严格落在预览矩形内部。本类型不含任何 Android 依赖，
 * 行为测试直接驱动同一份数学。
 */
/** 手指仍可抓住的最小裁剪框宽度（dp）；不超过图片本身。 */
const val MIN_CROP_WIDTH: Float = 80f

/** 预览坐标或像素坐标下的矩形。 */
data class CropRect(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

/** 交给解码位图裁剪的整数像素窗口。 */
data class CropPixelRegion(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

object CropGeometry {

    private fun clampValue(value: Float, low: Float, high: Float): Float =
        value.coerceIn(low, maxOf(high, low))

    private fun validRect(rect: CropRect?): Boolean =
        rect != null && rect.width > 0f && rect.height > 0f &&
            !rect.x.isNaN() && !rect.y.isNaN()

    /**
     * 图片在预览区内的渲染矩形（Contain 居中）。
     * 布局未完成或图片退化时返回 null，调用方必须禁止确认而不是除零。
     */
    fun fitDisplay(areaWidth: Float, areaHeight: Float, pixelWidth: Int, pixelHeight: Int): CropRect? {
        if (areaWidth <= 0f || areaHeight <= 0f || pixelWidth <= 0 || pixelHeight <= 0) {
            return null
        }
        val scale = minOf(areaWidth / pixelWidth, areaHeight / pixelHeight)
        if (scale <= 0f || !scale.isFinite()) {
            return null
        }
        val rect = CropRect(
            x = (areaWidth - pixelWidth * scale) / 2f,
            y = (areaHeight - pixelHeight * scale) / 2f,
            width = pixelWidth * scale,
            height = pixelHeight * scale,
        )
        return rect.takeIf { validRect(it) }
    }

    /**
     * 锁定比例的裁剪框在图片矩形内能取到的最大宽度。图片自身是硬上限：
     * 极窄的图片给出极小的上限，且该上限优先于 [MIN_CROP_WIDTH]。
     */
    fun maxCropWidth(display: CropRect?, aspect: Float): Float {
        if (aspect <= 0f || !validRect(display)) {
            return 0f
        }
        val safe = requireNotNull(display)
        return minOf(safe.width, safe.height * aspect)
    }

    /**
     * 裁剪页打开时的初始框：居中的最大锁定比例拟合，绝不超出图片渲染范围。
     * 布局未就绪或比例非法时返回 null。
     */
    fun initialCrop(display: CropRect?, aspect: Float): CropRect? {
        if (display == null || aspect <= 0f || !validRect(display)) {
            return null
        }
        val maxWidth = maxCropWidth(display, aspect)
        if (maxWidth <= 0f) {
            return null
        }
        val rect = CropRect(
            x = display.x + (display.width - maxWidth) / 2f,
            y = display.y + (display.height - maxWidth / aspect) / 2f,
            width = maxWidth,
            height = maxWidth / aspect,
        )
        return clampCrop(rect, display, aspect)
    }

    /**
     * 页面唯一生效的边界约束：拖动、缩放、重置与布局变化后都必须过这里。
     * 宽度收敛到 [min(MIN_CROP_WIDTH, 上限), 上限]，且整个框保持在图片矩形内。
     * 边界本身未定义（未完成布局）时返回 null。
     */
    fun clampCrop(crop: CropRect?, display: CropRect?, aspect: Float): CropRect? {
        if (aspect <= 0f || !validRect(display)) {
            return null
        }
        val safeDisplay = requireNotNull(display)
        val maxWidth = maxCropWidth(safeDisplay, aspect)
        if (maxWidth <= 0f) {
            return null
        }
        val minWidth = minOf(MIN_CROP_WIDTH, maxWidth)
        val safeCrop = crop ?: return null
        val width = clampValue(safeCrop.width, minWidth, maxWidth)
        val height = width / aspect
        val rect = CropRect(
            x = clampValue(safeCrop.x, safeDisplay.x, safeDisplay.x + safeDisplay.width - width),
            y = clampValue(safeCrop.y, safeDisplay.y, safeDisplay.y + safeDisplay.height - height),
            width = width,
            height = height,
        )
        return rect.takeIf { validRect(it) }
    }

    /**
     * 预览区或比例变化后（旋转、分屏、窗口尺寸变化）重摆裁剪框：保留用户的选择——
     * 中心在图片内的相对位置不变，宽度占旧上限的比例不变；新左上角 = 新中心 -
     * 新框一半，再夹进新边界。对未变化的布局重复调用是空操作。
     * 新布局不存在时返回 null。
     */
    fun recenterCrop(
        oldCrop: CropRect?,
        oldDisplay: CropRect?,
        oldAspect: Float,
        newDisplay: CropRect?,
        newAspect: Float,
    ): CropRect? {
        val initial = initialCrop(newDisplay, newAspect) ?: return null
        if (oldCrop == null || !validRect(oldCrop) ||
            oldDisplay == null || !validRect(oldDisplay) || oldAspect <= 0f
        ) {
            return initial
        }
        val oldMax = maxCropWidth(oldDisplay, oldAspect)
        if (oldMax <= 0f) {
            return initial
        }
        val widthRatio = clampValue(oldCrop.width / oldMax, 0f, 1f)
        val centreRatioX = if (oldDisplay.width > 0f) {
            clampValue((oldCrop.x + oldCrop.width / 2f - oldDisplay.x) / oldDisplay.width, 0f, 1f)
        } else {
            0.5f
        }
        val centreRatioY = if (oldDisplay.height > 0f) {
            clampValue((oldCrop.y + oldCrop.height / 2f - oldDisplay.y) / oldDisplay.height, 0f, 1f)
        } else {
            0.5f
        }
        val newMax = maxCropWidth(requireNotNull(newDisplay), newAspect)
        if (newMax <= 0f) {
            return initial
        }
        val newWidth = widthRatio * newMax
        val newHeight = newWidth / newAspect
        // 先定位中心，再回退半框：满宽的框保持左缘贴住图片左缘，而不是漂移半个余量。
        val wanted = CropRect(
            x = requireNotNull(newDisplay).x + centreRatioX * newDisplay.width - newWidth / 2f,
            y = newDisplay.y + centreRatioY * newDisplay.height - newHeight / 2f,
            width = newWidth,
            height = newHeight,
        )
        return clampCrop(wanted, newDisplay, newAspect)
    }

    /**
     * 把相对一张位图计算的像素窗口映射到另一张尺寸不同的位图上
     * （保存路径独立解码，不能假定请求尺寸与实际尺寸一致）。整数输出，
     * 完全夹在目标位图内；同尺寸映射为恒等。
     */
    fun scaleRegion(
        region: CropPixelRegion?,
        fromWidth: Int,
        fromHeight: Int,
        toWidth: Int,
        toHeight: Int,
    ): CropPixelRegion? {
        if (fromWidth <= 0 || fromHeight <= 0 || toWidth <= 0 || toHeight <= 0 ||
            region == null || region.width <= 0 || region.height <= 0
        ) {
            return null
        }
        if (fromWidth == toWidth && fromHeight == toHeight) {
            return region
        }
        val scaledX = region.x.toFloat() * toWidth / fromWidth
        val scaledY = region.y.toFloat() * toHeight / fromHeight
        val scaledWidth = region.width.toFloat() * toWidth / fromWidth
        val scaledHeight = region.height.toFloat() * toHeight / fromHeight
        if (!scaledX.isFinite() || !scaledY.isFinite() ||
            !scaledWidth.isFinite() || !scaledHeight.isFinite()
        ) {
            return null
        }
        val x = clampValue(scaledX, 0f, (toWidth - 1).toFloat()).toInt()
        val y = clampValue(scaledY, 0f, (toHeight - 1).toFloat()).toInt()
        val width = clampValue(scaledWidth, 1f, (toWidth - x).toFloat()).toInt()
        val height = clampValue(scaledHeight, 1f, (toHeight - y).toFloat()).toInt()
        return CropPixelRegion(x = x, y = y, width = width, height = height)
    }

    /**
     * 把预览矩形裁剪框换算成整数像素窗口，完全夹在（已转正的）解码像素内。
     * 返回 null 时调用方必须拒绝确认——这是对除零、NaN 与越界窗口的统一防线。
     */
    fun pixelRegion(
        crop: CropRect?,
        display: CropRect?,
        pixelWidth: Int,
        pixelHeight: Int,
    ): CropPixelRegion? {
        if (!validRect(crop) || !validRect(display) || pixelWidth <= 0 || pixelHeight <= 0) {
            return null
        }
        val safeDisplay = requireNotNull(display)
        val safeCrop = requireNotNull(crop)
        val scale = minOf(safeDisplay.width / pixelWidth, safeDisplay.height / pixelHeight)
        if (scale <= 0f || !scale.isFinite()) {
            return null
        }
        val x = (safeCrop.x - safeDisplay.x) / scale
        val y = (safeCrop.y - safeDisplay.y) / scale
        val width = safeCrop.width / scale
        val height = safeCrop.height / scale
        if (!x.isFinite() || !y.isFinite() || !width.isFinite() || !height.isFinite()) {
            return null
        }
        val clampedX = clampValue(x, 0f, (pixelWidth - 1).toFloat()).toInt()
        val clampedY = clampValue(y, 0f, (pixelHeight - 1).toFloat()).toInt()
        val clampedWidth = clampValue(width, 1f, (pixelWidth - clampedX).toFloat()).toInt()
        val clampedHeight = clampValue(height, 1f, (pixelHeight - clampedY).toFloat()).toInt()
        return CropPixelRegion(
            x = clampedX,
            y = clampedY,
            width = clampedWidth,
            height = clampedHeight,
        )
    }
}
