package io.github.ancientcatz.lime.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import io.github.ancientcatz.lime.R
import io.github.ancientcatz.lime.ui.main.MainFragment

class MainActivity : AppCompatActivity() {

    companion object {
        fun gotoFragment(
            fragmentManager: FragmentManager,
            fragment: Fragment,
            tag: String,
            addToBackStack: Boolean = true
        ) {
            if (fragmentManager.findFragmentByTag(tag) == null) {
                val fragmentTransaction = fragmentManager.beginTransaction()
                    .replace(R.id.settings_container, fragment, tag)
                if (addToBackStack) {
                    fragmentTransaction
                        .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                        .addToBackStack(null)
                }
                fragmentTransaction.commit()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        gotoFragment(
            supportFragmentManager,
            MainFragment(),
            MainFragment.TAG,
            false
        )
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}