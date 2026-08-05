package com.autoclicker

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.autoclicker.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnOpenAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.switchTimed.setOnCheckedChangeListener { _, isChecked ->
            ScenarioStore.setTimedEnabled(this, isChecked)
        }

        binding.btnUsePicked.setOnClickListener {
            val (x, y) = ScenarioStore.getPicked(this)
            if (x > 0 && y > 0) {
                binding.etTimedX.setText(x.toString())
                binding.etTimedY.setText(y.toString())
                ScenarioStore.setTimedCoord(this, x, y)
                Toast.makeText(this, "已填入坐标 ($x, $y)", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "请先在悬浮窗拖动圆点并点「保存坐标」", Toast.LENGTH_LONG).show()
            }
        }

        binding.btnSaveTimed.setOnClickListener {
            val interval = binding.etInterval.text.toString().toIntOrNull() ?: 5000
            ScenarioStore.setInterval(this, interval.coerceAtLeast(500))
            val x = binding.etTimedX.text.toString().toIntOrNull() ?: 0
            val y = binding.etTimedY.text.toString().toIntOrNull() ?: 0
            ScenarioStore.setTimedCoord(this, x, y)
            Toast.makeText(this, "定时设置已保存", Toast.LENGTH_SHORT).show()
        }

        // v6 新增：保存配置并返回按钮
        binding.btnSaveAndBack.setOnClickListener {
            saveAllSettings()
            Toast.makeText(this, "✓ 配置已保存，返回悬浮窗", Toast.LENGTH_SHORT).show()
            finish()
        }

        binding.fabAdd.setOnClickListener { showAddDialog() }
        refreshUi()
    }

    override fun onResume() { super.onResume(); refreshUi() }

    /** v6：统一保存当前界面所有设置 */
    private fun saveAllSettings() {
        val interval = binding.etInterval.text.toString().toIntOrNull() ?: 5000
        ScenarioStore.setInterval(this, interval.coerceAtLeast(500))
        val x = binding.etTimedX.text.toString().toIntOrNull() ?: 0
        val y = binding.etTimedY.text.toString().toIntOrNull() ?: 0
        ScenarioStore.setTimedCoord(this, x, y)
    }

    private fun refreshUi() {
        binding.switchTimed.isChecked = ScenarioStore.isTimedEnabled(this)
        val (tx, ty) = ScenarioStore.getTimedCoord(this)
        if (tx > 0) binding.etTimedX.setText(tx.toString())
        if (ty > 0) binding.etTimedY.setText(ty.toString())
        binding.etInterval.setText(ScenarioStore.getInterval(this).toString())

        binding.scenarioList.removeAllViews()
        val scenarios = ScenarioStore.load(this)
        for (s in scenarios) {
            val row = LayoutInflater.from(this).inflate(R.layout.row_scenario, binding.scenarioList, false)
            row.findViewById<TextView>(R.id.tvName).text = s.name.ifBlank { "(未命名)" }
            row.findViewById<TextView>(R.id.tvMatch).text =
                "匹配: ${s.matchText.ifBlank { "（空）" }}  ·  ${if (s.action == "node") "点击该文字" else "点击坐标(${s.clickX},${s.clickY})"}"
            val sw = row.findViewById<Switch>(R.id.swEnabled)
            sw.isChecked = s.enabled
            sw.setOnCheckedChangeListener { _, c -> ScenarioStore.update(this, s.copy(enabled = c)) }
            row.findViewById<ImageButton>(R.id.btnDelete).setOnClickListener {
                ScenarioStore.remove(this, s.id); refreshUi()
            }
            binding.scenarioList.addView(row)
        }
        binding.tvEmpty.visibility = if (scenarios.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showAddDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_scenario, null)
        val etName = view.findViewById<EditText>(R.id.etName)
        val etMatch = view.findViewById<EditText>(R.id.etMatch)
        val spinner = view.findViewById<Spinner>(R.id.spinnerAction)
        val etX = view.findViewById<EditText>(R.id.etX)
        val etY = view.findViewById<EditText>(R.id.etY)
        val btnUsePicked = view.findViewById<Button>(R.id.btnUsePicked)

        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("点击该文字节点", "点击指定坐标"))

        btnUsePicked.setOnClickListener {
            val (x, y) = ScenarioStore.getPicked(this)
            etX.setText(x.toString()); etY.setText(y.toString())
        }

        AlertDialog.Builder(this).setTitle("新增场景").setView(view)
            .setPositiveButton("保存") { _, _ ->
                val action = if (spinner.selectedItemPosition == 0) "node" else "coord"
                val s = Scenario(name = etName.text.toString(), matchText = etMatch.text.toString(),
                    action = action, clickX = etX.text.toString().toIntOrNull() ?: 0,
                    clickY = etY.text.toString().toIntOrNull() ?: 0)
                ScenarioStore.add(this, s); refreshUi()
                Toast.makeText(this, "✓ 场景「${s.name}」已保存", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("取消", null).show()
    }
}
