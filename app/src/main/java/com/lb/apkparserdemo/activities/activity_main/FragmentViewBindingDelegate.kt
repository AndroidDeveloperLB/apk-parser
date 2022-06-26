package com.lb.apkparserdemo.activities.activity_main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

fun AppCompatActivity.setContentView(binding: ViewBinding) = setContentView(binding.root)

open class BoundViewHolder<ViewBindingType : ViewBinding>(val binding: ViewBindingType, holderView: View = binding.root) : RecyclerView.ViewHolder(holderView)

/**usage:     private val binding by viewBinding(MainActivityBinding::inflate)*/
inline fun <T : ViewBinding> AppCompatActivity.viewBinding(crossinline bindingInflater: (LayoutInflater) -> T) =
    lazy(LazyThreadSafetyMode.NONE) {
        bindingInflater.invoke(layoutInflater)
    }

/**usage:  Fragment(R.layout.first_fragment)
 *  private val binding by viewBinding(FirstFragmentBinding::bind)*/
fun <T : ViewBinding> Fragment.viewBinding(viewBindingFactory: (View) -> T) =
    FragmentViewBindingDelegate(this, viewBindingFactory)

abstract class BoundActivity<T : ViewBinding>(private val factory: (LayoutInflater) -> T) :
    AppCompatActivity() {
    @Suppress("MemberVisibilityCanBePrivate")
    lateinit var binding: T

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = factory(layoutInflater)
        setContentView(binding.root)
    }
}

abstract class BoundFragment<T : ViewBinding>(
    private val factory: (LayoutInflater, ViewGroup?, Boolean) -> T
) : Fragment() {
    @Suppress("MemberVisibilityCanBePrivate")
    protected var binding: T? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = factory(inflater, container, false)
        return binding?.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}

class FragmentViewBindingDelegate<T : ViewBinding>(val fragment: Fragment, val viewBindingFactory: (View) -> T) :
    ReadOnlyProperty<Fragment, T> {
    private var binding: T? = null

    init {
        fragment.lifecycle.addObserver(object : DefaultLifecycleObserver {
            val viewLifecycleOwnerLiveDataObserver =
                Observer<LifecycleOwner?> {
                    val viewLifecycleOwner = it ?: return@Observer
                    viewLifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
                        override fun onDestroy(owner: LifecycleOwner) {
                            binding = null
                        }
                    })
                }

            override fun onCreate(owner: LifecycleOwner) {
                fragment.viewLifecycleOwnerLiveData.observeForever(viewLifecycleOwnerLiveDataObserver)
            }

            override fun onDestroy(owner: LifecycleOwner) {
                fragment.viewLifecycleOwnerLiveData.removeObserver(viewLifecycleOwnerLiveDataObserver)
            }
        })
    }

    override fun getValue(thisRef: Fragment, property: KProperty<*>): T {
        binding?.let { return it }
        val lifecycle = fragment.viewLifecycleOwner.lifecycle
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.INITIALIZED))
            throw IllegalStateException("Should not attempt to get bindings when Fragment views are destroyed.")
        return viewBindingFactory(thisRef.requireView()).also { this.binding = it }
    }
}
