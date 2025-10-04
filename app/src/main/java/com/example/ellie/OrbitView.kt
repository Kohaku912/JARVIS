package com.example.ellie

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import kotlin.math.*
import kotlin.random.Random

data class OrbitConfig(
    val radius: Float,
    val speed: Float,
    val tilt: Float,
    val rotation: Float,
    val electronCount: Int,
    val color: Int = Color.parseColor("#9C27B0")
)

private data class ElectronParams(
    val x: Float,
    val y: Float,
    val z: Float,
    val orbitIndex: Int,
    val electronIndex: Int
)

private data class OrbitParams(
    val radius: Float,
    val tilt: Float,
    val yRotation: Float,
    val z: Float,
    val index: Int
)

private data class Particle(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    var life: Float,
    var maxLife: Float,
    val color: Int
)

class OrbitView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), Choreographer.FrameCallback {

    // カラーパレット（紫系のグラデーション）
    private val colorPalette = listOf(
        Color.parseColor("#E1BEE7"),  // 薄紫
        Color.parseColor("#CE93D8"),  // ライトパープル
        Color.parseColor("#BA68C8"),  // ミディアムパープル
        Color.parseColor("#9C27B0"),  // パープル
        Color.parseColor("#7B1FA2")   // ダークパープル
    )

    private val orbitConfigs = listOf(
        OrbitConfig(1.0f, 2.5f, 0f, 0f, 2, colorPalette[0]),
        OrbitConfig(1.7f, 1.8f, PI.toFloat() / 6f, PI.toFloat() / 4f, 4, colorPalette[1]),
        OrbitConfig(1.7f, 1.8f, -PI.toFloat() / 6f, PI.toFloat() / 2f, 4, colorPalette[2]),
        OrbitConfig(2.4f, 1.2f, PI.toFloat() / 4f, 0f, 6, colorPalette[3]),
        OrbitConfig(2.4f, 1.2f, -PI.toFloat() / 4f, PI.toFloat() / 3f, 6, colorPalette[4])
    )

    // パーティクルシステム
    private val particles = mutableListOf<Particle>()
    private val maxParticles = 50

    // ペイントオブジェクト
    private val paintElectron = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val paintElectronGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        maskFilter = BlurMaskFilter(20f, BlurMaskFilter.Blur.NORMAL)
    }

    private val paintOrbit = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        pathEffect = DashPathEffect(floatArrayOf(10f, 5f), 0f)
    }

    private val paintNucleus = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        shader = RadialGradient(
            0f, 0f, 15f,
            intArrayOf(
                Color.parseColor("#FFFFFF"),
                Color.parseColor("#CE93D8"),
                Color.parseColor("#9C27B0")
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private val paintNucleusGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#BA68C8")
        alpha = 100
        maskFilter = BlurMaskFilter(30f, BlurMaskFilter.Blur.NORMAL)
    }

    private val paintParticle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val paintTrail = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
    }

    private var startTimeNanos: Long = 0L
    private var elapsedTime: Float = 0f
    private val electronTrails = mutableMapOf<String, MutableList<PointF>>()
    private val trailLength = 15

    init {
        // ハードウェアアクセラレーションを有効化
        setLayerType(LAYER_TYPE_HARDWARE, null)
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (startTimeNanos == 0L) startTimeNanos = frameTimeNanos
        val deltaTime = if (elapsedTime > 0) {
            ((frameTimeNanos - startTimeNanos) / 1_000_000_000f) - elapsedTime
        } else 0.016f
        elapsedTime = (frameTimeNanos - startTimeNanos) / 1_000_000_000f

        updateParticles(deltaTime)
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun updateParticles(deltaTime: Float) {
        // パーティクルの更新
        particles.removeAll { particle ->
            particle.life -= deltaTime
            particle.x += particle.vx * deltaTime * 50f
            particle.y += particle.vy * deltaTime * 50f
            particle.life <= 0
        }

        // 新しいパーティクルの生成
        if (particles.size < maxParticles && Random.nextFloat() < 0.3f) {
            val angle = Random.nextFloat() * 2 * PI.toFloat()
            val speed = Random.nextFloat() * 2f + 1f
            particles.add(
                Particle(
                    x = 0f,
                    y = 0f,
                    vx = cos(angle) * speed,
                    vy = sin(angle) * speed,
                    life = Random.nextFloat() * 2f + 1f,
                    maxLife = 3f,
                    color = colorPalette.random()
                )
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 背景描画
        val bgRadius = max(width, height).toFloat()
        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        canvas.scale(bgRadius, bgRadius)


        canvas.restore()

        val viewSize = min(width, height).toFloat()
        val scale = viewSize / 4.5f
        val cx = width / 2f
        val cy = height / 2f

        canvas.translate(cx, cy)

        val sceneYRot = elapsedTime * 0.15f
        val pulseFactor = (sin(elapsedTime * 2f) * 0.1f + 1f)

        // パーティクル描画（背景）
        drawParticles(canvas, scale)

        // 軌道と電子の計算
        val electrons = mutableListOf<ElectronParams>()
        val orbits = mutableListOf<OrbitParams>()

        for ((index, cfg) in orbitConfigs.withIndex()) {
            val orbitZ = sin(cfg.tilt) * cfg.radius
            val phase = elapsedTime * cfg.speed * 2f
            orbits += OrbitParams(
                radius = cfg.radius,
                tilt = cfg.tilt,
                yRotation = sceneYRot + cfg.rotation + phase,
                z = orbitZ,
                index = index
            )

            for (i in 0 until cfg.electronCount) {
                val angleOff = i * 2 * PI.toFloat() / cfg.electronCount
                val angle = angleOff + phase
                val baseX = cos(angle) * cfg.radius
                val baseY = sin(angle) * cfg.radius
                val ty = baseY * cos(cfg.tilt)
                val tz = baseY * sin(cfg.tilt)
                val totalYrot = cfg.rotation + sceneYRot
                val fx = baseX * cos(totalYrot) + tz * sin(totalYrot)
                val fy = ty
                val fz = -baseX * sin(totalYrot) + tz * cos(totalYrot)
                electrons += ElectronParams(fx, fy, fz, index, i)
            }
        }

        // 軌道描画
        for (o in orbits) {
            drawOrbit(canvas, o, scale)
        }

        // 電子描画（奥から手前へ）
        electrons.sortedBy { it.z }.forEach { e ->
            drawElectron(canvas, e, scale, pulseFactor)
        }

        // 原子核
        drawNucleus(canvas, pulseFactor)
    }

    private fun drawElectron(canvas: Canvas, e: ElectronParams, scale: Float, pulseFactor: Float) {
        val persp = 10f / (10f + e.z)
        val px = e.x * persp * scale
        val py = e.y * persp * scale
        val size = 3f * pulseFactor * persp

        // トレイルの更新
        val trailKey = "${e.orbitIndex}_${e.electronIndex}"
        val trail = electronTrails.getOrPut(trailKey) { mutableListOf() }
        trail.add(PointF(px, py))
        if (trail.size > trailLength) {
            trail.removeAt(0)
        }

        // トレイル描画
        if (trail.size > 1) {
            val path = Path()
            for (i in 0 until trail.size - 1) {
                val alpha = (i.toFloat() / trail.size * 0.5f * 255).toInt()
                paintTrail.color = orbitConfigs[e.orbitIndex].color
                paintTrail.alpha = alpha
                paintTrail.strokeWidth = (i.toFloat() / trail.size * 3f)

                if (i == 0) {
                    path.moveTo(trail[i].x, trail[i].y)
                } else {
                    path.lineTo(trail[i].x, trail[i].y)
                }
                canvas.drawPath(path, paintTrail)
            }
        }

        // グロー効果
        paintElectronGlow.color = orbitConfigs[e.orbitIndex].color
        paintElectronGlow.alpha = 50
        canvas.drawCircle(px, py, size * 3f, paintElectronGlow)

        // 電子本体
        paintElectron.color = orbitConfigs[e.orbitIndex].color
        canvas.drawCircle(px, py, size, paintElectron)

        // 中心の光点
//        paintElectron.color = Color.WHITE
//        canvas.drawCircle(px - size * 0.3f, py - size * 0.3f, size * 0.3f, paintElectron)
    }

    private fun drawOrbit(canvas: Canvas, o: OrbitParams, scale: Float) {
        val persp = 10f / (10f + o.z)
        val r = o.radius * scale * persp
        val ry = r * cos(o.tilt)

        paintOrbit.color = orbitConfigs[o.index].color
        paintOrbit.alpha = (0.3f * 255 * persp).toInt()
        paintOrbit.pathEffect = DashPathEffect(
            floatArrayOf(20f * persp, 10f * persp),
            elapsedTime * 50f
        )

        canvas.save()
        canvas.rotate(o.yRotation * 180f / PI.toFloat())
        canvas.drawOval(
            RectF(-r, -ry, r, ry),
            paintOrbit
        )
        canvas.restore()
    }

    private fun drawNucleus(canvas: Canvas, pulseFactor: Float) {
        val nucleusSize = 8f * pulseFactor

        // グロー効果
        canvas.drawCircle(0f, 0f, nucleusSize * 3f, paintNucleusGlow)

        // 核本体
        canvas.drawCircle(0f, 0f, nucleusSize, paintNucleus)

        // エネルギーパルス
        val pulseAlpha = ((sin(elapsedTime * 4f) + 1f) * 0.5f * 100f).toInt()
        paintNucleusGlow.alpha = pulseAlpha
        canvas.drawCircle(0f, 0f, nucleusSize * 2f, paintNucleusGlow)
    }

    private fun drawParticles(canvas: Canvas, scale: Float) {
        particles.forEach { particle ->
            val alpha = (particle.life / particle.maxLife * 255).toInt()
            paintParticle.color = particle.color
            paintParticle.alpha = alpha

            val size = (particle.life / particle.maxLife) * 3f
            canvas.drawCircle(
                particle.x * scale * 0.5f,
                particle.y * scale * 0.5f,
                size,
                paintParticle
            )
        }
    }
}