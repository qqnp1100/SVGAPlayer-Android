package com.example.ponycui_home.svgaplayer

import android.content.Intent
import android.database.DataSetObserver
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ListAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.opensource.svgaplayer.SVGAParser.Companion.shareParser
import com.opensource.svgaplayer.utils.log.SVGALogger.setLogEnabled

class SampleItem(var title: String, var intent: Intent)
class MainActivity : AppCompatActivity() {
    var listView: ListView? = null
    var items: ArrayList<SampleItem> = ArrayList()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupData()
        setupListView()
        setupSVGAParser()
        setupLogger()
        setContentView(listView)
    }

    fun setupData() {
        items.add(
            SampleItem(
                "Animation From Assets",
                Intent(this, AnimationFromAssetsActivity::class.java)
            )
        )
        items.add(
            SampleItem(
                "Animation From Network",
                Intent(this, AnimationFromNetworkActivity::class.java)
            )
        )
        items.add(
            SampleItem(
                "Animation From Layout XML",
                Intent(this, AnimationFromLayoutActivity::class.java)
            )
        )
        items.add(
            SampleItem(
                "Animation With Dynamic Image",
                Intent(this, AnimationWithDynamicImageActivity::class.java)
            )
        )
        items.add(
            SampleItem(
                "Animation With Dynamic Click",
                Intent(this, AnimationFromClickActivity::class.java)
            )
        )
    }

    fun setupListView() {
        listView = ListView(this)
        listView?.adapter = object : ListAdapter {
            override fun areAllItemsEnabled(): Boolean {
                return false
            }

            override fun isEnabled(i: Int): Boolean {
                return false
            }

            override fun registerDataSetObserver(dataSetObserver: DataSetObserver) {}
            override fun unregisterDataSetObserver(dataSetObserver: DataSetObserver) {}
            override fun getCount(): Int {
                return items.size
            }

            override fun getItem(i: Int): Any? {
                return items[i]
            }

            override fun getItemId(i: Int): Long {
                return i.toLong()
            }

            override fun hasStableIds(): Boolean {
                return false
            }

            override fun getView(i: Int, view: View?, viewGroup: ViewGroup): View {
                val linearLayout = LinearLayout(this@MainActivity)
                val textView = TextView(this@MainActivity)
                textView.setOnClickListener {
                    this@MainActivity.startActivity(
                        items[i]?.intent
                    )
                }
                textView.text = items[i]!!.title
                textView.textSize = 24f
                textView.gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
                linearLayout.addView(
                    textView,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        (55 * resources.displayMetrics.density).toInt()
                    )
                )
                return linearLayout
            }

            override fun getItemViewType(i: Int): Int {
                return 1
            }

            override fun getViewTypeCount(): Int {
                return 1
            }

            override fun isEmpty(): Boolean {
                return false
            }
        }
        listView?.setBackgroundColor(Color.WHITE)
    }

    fun setupSVGAParser() {
        shareParser().init(this)
    }

    private fun setupLogger() {
        setLogEnabled(true)
    }
}