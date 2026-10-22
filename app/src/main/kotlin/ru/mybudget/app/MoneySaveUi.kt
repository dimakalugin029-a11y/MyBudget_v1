package ru.mybudget.app

import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

object MoneySaveUi {
    fun bind(activity:AppCompatActivity,model:MoneySaveViewModel,isSaving:()->Boolean,onState:(MoneySaveViewModel.State)->Unit) {
        activity.onBackPressedDispatcher.addCallback(activity,object:OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if(!isSaving()) activity.finish() }
        })
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.RESUMED) { model.state.collect { onState(it) } }
        }
    }
}
