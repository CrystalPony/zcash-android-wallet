package cash.z.ecc.android.ui.home

import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import cash.z.ecc.android.R
import cash.z.ecc.android.databinding.FragmentHomeBinding
import cash.z.ecc.android.di.annotation.FragmentScope
import cash.z.ecc.android.ext.disabledIf
import cash.z.ecc.android.ext.goneIf
import cash.z.ecc.android.ext.onClickNavTo
import cash.z.ecc.android.ui.base.BaseFragment
import cash.z.ecc.android.ui.home.HomeFragment.BannerAction.*
import cash.z.ecc.android.ui.setup.WalletSetupViewModel
import cash.z.ecc.android.ui.setup.WalletSetupViewModel.WalletSetupState.NO_SEED
import cash.z.wallet.sdk.SdkSynchronizer
import cash.z.wallet.sdk.Synchronizer.Status.SYNCING
import cash.z.wallet.sdk.ext.convertZatoshiToZecString
import cash.z.wallet.sdk.ext.convertZecToZatoshi
import cash.z.wallet.sdk.ext.safelyConvertToBigDecimal
import cash.z.wallet.sdk.ext.twig
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.Module
import dagger.android.ContributesAndroidInjector
import kotlinx.coroutines.channels.ConflatedBroadcastChannel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

class HomeFragment : BaseFragment<FragmentHomeBinding>() {

    private lateinit var numberPad: List<TextView>
    private lateinit var uiModel: HomeViewModel.UiModel

    @Inject
    lateinit var viewModelFactory: ViewModelProvider.Factory

    private val walletSetup: WalletSetupViewModel by activityViewModels { viewModelFactory }
    private val viewModel: HomeViewModel by activityViewModels { viewModelFactory }

    private val _typedChars = ConflatedBroadcastChannel<Char>()
    private val typedChars = _typedChars.asFlow()

    override fun inflate(inflater: LayoutInflater): FragmentHomeBinding =
        FragmentHomeBinding.inflate(inflater)


    //
    // LifeCycle
    //

    override fun onAttach(context: Context) {
        twig("HomeFragment.onAttach")
        super.onAttach(context)

        // call initSync either now or later (after initializing DBs with newly created seed)
        walletSetup.checkSeed().onEach {
            twig("Checking seed")
            when(it) {
                NO_SEED -> {
                    twig("Seed not found, therefore, launching seed creation flow")
                    // interact with user to create, backup and verify seed
                    mainActivity?.navController?.navigate(R.id.action_nav_home_to_create_wallet)
                    // leads to a call to initSync(), later (after accounts are created from seed)
                }
                else -> {
                    twig("Found seed. Re-opening existing wallet")
                    mainActivity?.initSync()
                }
            }
        }.launchIn(lifecycleScope)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        twig("HomeFragment.onViewCreated  uiModel: ${::uiModel.isInitialized}  saved: ${savedInstanceState != null}")
        with(binding) {
            numberPad = arrayListOf(
                buttonNumberPad0.asKey(),
                buttonNumberPad1.asKey(),
                buttonNumberPad2.asKey(),
                buttonNumberPad3.asKey(),
                buttonNumberPad4.asKey(),
                buttonNumberPad5.asKey(),
                buttonNumberPad6.asKey(),
                buttonNumberPad7.asKey(),
                buttonNumberPad8.asKey(),
                buttonNumberPad9.asKey(),
                buttonNumberPadDecimal.asKey(),
                buttonNumberPadBack.asKey()
            )
            hitAreaReceive.onClickNavTo(R.id.action_nav_home_to_nav_receive)
            iconDetail.onClickNavTo(R.id.action_nav_home_to_nav_detail)
            textDetail.onClickNavTo(R.id.action_nav_home_to_nav_detail)
//            hitAreaScan.onClickNavTo(R.id.action_nav_home_to_nav_send)

            textBannerAction.setOnClickListener {
                onBannerAction(BannerAction.from((it as? TextView)?.text?.toString()))
            }
            buttonSend.setOnClickListener {
                onSend()
            }
        }
        if (::uiModel.isInitialized) {
            twig("uiModel exists!")
            onModelUpdated(HomeViewModel.UiModel(), uiModel)
        } else {
            twig("uiModel does not exist!")
            mainActivity?.onSyncInit {
                viewModel.initialize(mainActivity!!.synchronizer, typedChars)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        twig("HomeFragment.onResume  resumeScope.isActive: ${resumedScope.isActive}  $resumedScope")
        viewModel.uiModels.scanReduce { old, new ->
            onModelUpdated(old, new)
            new
        }.catch { e ->
            twig("exception while processing uiModels $e")
        }.launchIn(resumedScope)

        // TODO: see if there is a better way to trigger a refresh of the uiModel on resume
        //       the latest one should just be in the viewmodel and we should just "resubscribe"
        //       but for some reason, this doesn't always happen, which kind of defeats the purpose
        //       of having a cold stream in the view model
        resumedScope.launch {
            (mainActivity!!.synchronizer as SdkSynchronizer).refreshBalance()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        twig("HomeFragment.onSaveInstanceState")
        if (::uiModel.isInitialized) {
            outState.putParcelable("uiModel", uiModel)
        }
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        savedInstanceState?.let { inState ->
            twig("HomeFragment.onViewStateRestored")
            onModelUpdated(HomeViewModel.UiModel(), inState.getParcelable("uiModel")!!)
        }
    }


    //
    // Public UI API
    //

    fun setSendEnabled(enabled: Boolean) {
        binding.buttonSend.apply {
            isEnabled = enabled
            backgroundTintList = ColorStateList.valueOf( resources.getColor( if(enabled) R.color.colorPrimary else R.color.zcashWhite_24) )
        }
    }

    fun setProgress(progress: Int) {
        progress.let {
            if (it < 100) {
                setBanner("Downloading . . . $it%", NONE)
            } else {
                setBanner("Scanning . . .", NONE)
            }
        }
    }

    fun setSendAmount(amount: String) {
        binding.textSendAmount.text = "\$$amount"
        mainActivity?.sendViewModel?.zatoshiAmount = amount.safelyConvertToBigDecimal().convertZecToZatoshi()
        binding.buttonSend.disabledIf(amount == "0")
    }

    fun setAvailable(availableBalance: Long = -1L, totalBalance: Long = -1L) {
        val availableString = if (availableBalance < 0) "Updating" else availableBalance.convertZatoshiToZecString()
        binding.textBalanceAvailable.text = availableString
        binding.textBalanceDescription.apply {
            goneIf(availableBalance < 0)
            text = if (availableBalance != -1L && (availableBalance < totalBalance)) {
                "(expecting +${(totalBalance - availableBalance).convertZatoshiToZecString()} ZEC in change)"
            } else {
                "(enter an amount to send)"
            }
        }
    }

    fun setSendText(buttonText: String = "Send Amount") {
        binding.buttonSend.text = buttonText
    }

    fun setBanner(message: String = "", action: BannerAction = CLEAR) {
        with(binding) {
            val hasMessage = !message.isEmpty() || action != CLEAR
            groupBalance.goneIf(hasMessage)
            groupBanner.goneIf(!hasMessage)
            layerLock.goneIf(!hasMessage)

            textBannerMessage.text = message
            textBannerAction.text = action.action
        }
    }


    //
    // Private UI Events
    //

    private fun onModelUpdated(old: HomeViewModel.UiModel, new: HomeViewModel.UiModel) {
        twig(new.toString())
        uiModel = new
        if (old.pendingSend != new.pendingSend) {
            setSendAmount(new.pendingSend)
        }
        // TODO: handle stopped and disconnected flows
        if (new.status == SYNCING) onSyncing(new) else onSynced(new)
        setSendEnabled(new.isSendEnabled)
    }

    private fun onSyncing(uiModel: HomeViewModel.UiModel) {
        setProgress(uiModel.progress) // calls setBanner
        setAvailable()
        setSendText("Syncing Blockchain…")
    }

    private fun onSynced(uiModel: HomeViewModel.UiModel) {
        if (!uiModel.hasFunds) {
            onNoFunds()
        } else {
            setBanner("")
            setAvailable(uiModel.availableBalance, uiModel.totalBalance)
            setSendText()
        }
    }

    private fun onSend() {
        mainActivity?.navController?.navigate(R.id.action_nav_home_to_send)
    }

    private fun onBannerAction(action: BannerAction) {
        when (action) {
            FUND_NOW -> {
                MaterialAlertDialogBuilder(activity)
                    .setMessage("To make full use of this wallet, deposit funds to your address or tap the faucet to trigger a tiny automatic deposit.\n\nFaucet funds are made available for the community by the community for testing. So please be kind enough to return what you borrow!")
                    .setTitle("No Balance")
                    .setCancelable(true)
                    .setPositiveButton("Tap Faucet") { dialog, _ ->
                        dialog.dismiss()
                        setBanner("Tapping faucet...", CANCEL)
                    }
                    .setNegativeButton("View Address") { dialog, _ ->
                        dialog.dismiss()
                        mainActivity?.navController?.navigate(R.id.action_nav_home_to_nav_receive)
                    }
                    .show()
            }
            CANCEL -> {
                // TODO: trigger banner / balance update
                onNoFunds()
            }
        }
    }

    private fun onNoFunds() {
        setBanner("No Balance", FUND_NOW)
    }


    //
    // Inner classes and extensions
    //

    enum class BannerAction(val action: String) {
        FUND_NOW("Fund Now"),
        CANCEL("Cancel"),
        NONE(""),
        CLEAR("clear");

        companion object {
            fun from(action: String?): BannerAction {
                values().forEach {
                    if (it.action == action) return it
                }
                throw IllegalArgumentException("Invalid BannerAction: $action")
            }
        }
    }

    private fun TextView.asKey(): TextView {
        val c = text[0]
        setOnClickListener {
            lifecycleScope.launch {
                twig("CHAR TYPED: $c")
                _typedChars.send(c)
            }
        }
        return this
    }




    // TODO: remove these troubleshooting logs
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        twig("HomeFragment.onCreate")
    }
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        twig("HomeFragment.onActivityCreated")
    }
    override fun onStart() {
        super.onStart()
        twig("HomeFragment.onStart")
    }
    override fun onPause() {
        super.onPause()
        twig("HomeFragment.onPause  resumeScope.isActive: ${resumedScope.isActive}")
    }
    override fun onStop() {
        super.onStop()
        twig("HomeFragment.onStop")
    }
    override fun onDestroyView() {
        super.onDestroyView()
        twig("HomeFragment.onDestroyView")
    }
    override fun onDestroy() {
        super.onDestroy()
        twig("HomeFragment.onDestroy")
    }
    override fun onDetach() {
        super.onDetach()
        twig("HomeFragment.onDetach")
    }
}


@Module
abstract class HomeFragmentModule {
    @FragmentScope
    @ContributesAndroidInjector
    abstract fun contributeFragment(): HomeFragment
}