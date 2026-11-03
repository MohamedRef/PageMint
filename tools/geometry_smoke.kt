/** Standalone geometry sanity check: kotlinc is enough, no Android SDK or OpenCV required. */
import com.mohamedref.pagemint.cv.*
import kotlin.math.abs

private fun corners(w: Int, h: Int, p: Array<Pair<Double, Double>>) = p.map { (x, y) ->
    Corner((x / (w - 1)).toFloat(), (y / (h - 1)).toFloat())
}

fun main() {
    val frontal = corners(1000, 1000, arrayOf(200.0 to 100.0, 800.0 to 100.0,
        800.0 to 940.0, 200.0 to 940.0))
    val flat = RectificationGeometry.plan(1000, 1000, frontal)
    check(abs(flat.aspectRatio - 600.0 / 840.0) < 0.001)
    check(abs(flat.outputWidth / flat.outputHeight.toDouble() - 600.0 / 840.0) < 0.003)

    val photos = listOf(
        arrayOf(390.8907 to 115.2438, 812.5406 to 67.1034,
            832.6206 to 822.3946, 449.1712 to 721.7214),
        arrayOf(315.2869 to 148.0149, 618.5157 to 135.3115,
            892.5301 to 760.2763, 589.6832 to 625.3415),
    )
    for (photo in photos) {
        val photoCorners = corners(1200, 900, photo)
        val auto = RectificationGeometry.plan(1200, 900, photoCorners)
        check(abs(auto.aspectRatio - 210.0 / 297.0) < 0.004) { "Wrong auto ratio: $auto" }
        for (fmt in PageFormat.entries.filter { it.aspectRatio != null }) {
            val sized = RectificationGeometry.plan(1200, 900, photoCorners, fmt)
            check(abs(sized.outputWidth / sized.outputHeight.toDouble() - fmt.aspectRatio!!) < 0.003)
        }
    }
    val max = RectificationGeometry.plan(8000, 8000, QuadGeometry.fallback())
    check(max.outputWidth.toLong() * max.outputHeight <= 12_000_000L)
    check(max.outputWidth <= 4800 && max.outputHeight <= 4800)
    val border = QuadGeometry.withSafetyBorder(frontal)
    check(QuadGeometry.valid(border) && QuadGeometry.area(border) > QuadGeometry.area(frontal))
    check(QuadGeometry.withSafetyBorder(QuadGeometry.fallback()) == QuadGeometry.fallback())
    println("Perspective geometry smoke: PASS (2 tilted projections, 5 paper presets, pixel cap and safety border).")
    println("Frontal: ${flat.outputWidth}x${flat.outputHeight}; capped: ${max.outputWidth}x${max.outputHeight}")
}
