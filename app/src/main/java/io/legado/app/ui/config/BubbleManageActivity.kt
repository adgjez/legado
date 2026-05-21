package io.legado.app.ui.config

import android.os.Bundle
import android.view.View
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityThemeManageBinding
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.utils.viewbindingdelegate.viewBinding

class BubbleManageActivity : BaseActivity<ActivityThemeManageBinding>() {

    override val binding by viewBinding(ActivityThemeManageBinding::inflate)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.title = getString(R.string.bubble_manage)
        binding.root.applyUiBodyTypefaceDeep(uiTypeface())
        binding.tabBar.visibility = View.GONE
        binding.tvSummary.text = getString(R.string.bubble_manage_summary)
        binding.btnAdd.visibility = View.GONE
    }
}
