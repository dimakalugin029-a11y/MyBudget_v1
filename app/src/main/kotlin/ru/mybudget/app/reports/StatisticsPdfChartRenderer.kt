package ru.mybudget.app.reports

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import ru.mybudget.app.MoneyFormat
import ru.mybudget.app.StatisticsChartHelper

object StatisticsPdfChartRenderer {
    private const val maxPoints = 60

    fun drawBalanceChart(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        values: List<Double>,
        lineColor: Int,
        fillColor: Int,
        gridColor: Int,
        labelColor: Int,
    ) {
        if (values.isEmpty()) return
        val sampled = sample(values)
        val (yMin, yMax) = StatisticsChartHelper.yAxisBounds(sampled)
        drawFrame(canvas, left, top, right, bottom, gridColor)
        drawYLabels(canvas, left, top, bottom, yMin.toDouble(), yMax.toDouble(), labelColor)
        val plotLeft = left + 52f
        val plotRight = right - 8f
        val plotTop = top + 8f
        val plotBottom = bottom - 16f
        val path = Path()
        val fillPath = Path()
        sampled.forEachIndexed { index, value ->
            val x = plotLeft + (plotRight - plotLeft) * index / (sampled.lastIndex.coerceAtLeast(1))
            val y = mapY(value, yMin.toDouble(), yMax.toDouble(), plotTop, plotBottom)
            if (index == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, plotBottom)
                fillPath.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo(
            plotLeft + (plotRight - plotLeft),
            plotBottom,
        )
        fillPath.close()
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fillColor
            style = Paint.Style.FILL
        }
        canvas.drawPath(fillPath, fillPaint)
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = lineColor
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        canvas.drawPath(path, linePaint)
    }

    fun drawFlowChart(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        values: List<Double>,
        incomeColor: Int,
        expenseColor: Int,
        gridColor: Int,
        labelColor: Int,
    ) {
        if (values.isEmpty()) return
        val sampled = sample(values)
        val (yMin, yMax) = StatisticsChartHelper.yAxisBounds(sampled + listOf(0.0))
        drawFrame(canvas, left, top, right, bottom, gridColor)
        drawYLabels(canvas, left, top, bottom, yMin.toDouble(), yMax.toDouble(), labelColor)
        val plotLeft = left + 52f
        val plotRight = right - 8f
        val plotTop = top + 8f
        val plotBottom = bottom - 16f
        val zeroY = mapY(0.0, yMin.toDouble(), yMax.toDouble(), plotTop, plotBottom)
        canvas.drawLine(plotLeft, zeroY, plotRight, zeroY, Paint().apply {
            color = gridColor
            strokeWidth = 1f
        })
        val barWidth = (plotRight - plotLeft) / sampled.size * 0.65f
        sampled.forEachIndexed { index, value ->
            val centerX = plotLeft + (plotRight - plotLeft) * (index + 0.5f) / sampled.size
            val valueY = mapY(value, yMin.toDouble(), yMax.toDouble(), plotTop, plotBottom)
            val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (value >= 0.0) incomeColor else expenseColor
                style = Paint.Style.FILL
            }
            canvas.drawRect(
                centerX - barWidth / 2f,
                kotlin.math.min(valueY, zeroY),
                centerX + barWidth / 2f,
                kotlin.math.max(valueY, zeroY),
                barPaint,
            )
        }
    }

    private fun sample(values: List<Double>): List<Double> {
        if (values.size <= maxPoints) return values
        val step = values.size.toDouble() / maxPoints
        return (0 until maxPoints).map { index ->
            values[(index * step).toInt().coerceIn(0, values.lastIndex)]
        }
    }

    private fun drawFrame(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, gridColor: Int) {
        val framePaint = Paint().apply {
            color = gridColor
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        canvas.drawRect(left, top, right, bottom, framePaint)
    }

    private fun drawYLabels(
        canvas: Canvas,
        left: Float,
        top: Float,
        bottom: Float,
        yMin: Double,
        yMax: Double,
        labelColor: Int,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = labelColor
            textSize = 9f
        }
        listOf(yMax, (yMax + yMin) / 2.0, yMin).forEach { value ->
            val y = mapY(value, yMin, yMax, top + 8f, bottom - 16f)
            canvas.drawText(MoneyFormat.formatChartAxis(value), left + 4f, y + 3f, paint)
        }
    }

    private fun mapY(value: Double, yMin: Double, yMax: Double, top: Float, bottom: Float): Float {
        if (yMax == yMin) return (top + bottom) / 2f
        val ratio = ((value - yMin) / (yMax - yMin)).toFloat()
        return bottom - ratio * (bottom - top)
    }
}
