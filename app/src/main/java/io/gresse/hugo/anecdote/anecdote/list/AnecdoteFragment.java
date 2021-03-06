package io.gresse.hugo.anecdote.anecdote.list;

import android.app.ActivityOptions;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.preference.PreferenceManager;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.View;
import android.view.ViewGroup;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;

import javax.inject.Inject;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.Unbinder;
import io.gresse.hugo.anecdote.MainActivity;
import io.gresse.hugo.anecdote.R;
import io.gresse.hugo.anecdote.anecdote.UpdateAnecdoteFragmentEvent;
import io.gresse.hugo.anecdote.anecdote.fullscreen.FullscreenActivity;
import io.gresse.hugo.anecdote.anecdote.like.FavoritesRepository;
import io.gresse.hugo.anecdote.anecdote.model.Anecdote;
import io.gresse.hugo.anecdote.anecdote.service.AnecdoteService;
import io.gresse.hugo.anecdote.anecdote.service.event.FavoritesEvent;
import io.gresse.hugo.anecdote.anecdote.service.event.LoadNewAnecdoteEvent;
import io.gresse.hugo.anecdote.anecdote.service.event.OnAnecdoteLoadedEvent;
import io.gresse.hugo.anecdote.anecdote.service.event.UpdateAnecdoteEvent;
import io.gresse.hugo.anecdote.anecdote.social.CopyAnecdoteEvent;
import io.gresse.hugo.anecdote.anecdote.social.OpenAnecdoteEvent;
import io.gresse.hugo.anecdote.anecdote.social.ShareAnecdoteEvent;
import io.gresse.hugo.anecdote.api.model.Website;
import io.gresse.hugo.anecdote.api.model.WebsitePage;
import io.gresse.hugo.anecdote.event.ChangeTitleEvent;
import io.gresse.hugo.anecdote.event.RequestFailedEvent;
import io.gresse.hugo.anecdote.tracking.EventTracker;
import io.gresse.hugo.anecdote.util.TitledFragment;
import io.gresse.hugo.anecdote.view.EnhancedFrameLayout;
import toothpick.Scope;
import toothpick.Toothpick;

/**
 * A generic anecdote fragment
 * <p/>
 * Created by Hugo Gresse on 13/02/16.
 */
public class AnecdoteFragment extends TitledFragment implements
        SwipeRefreshLayout.OnRefreshListener,
        AdapterListener {

    private static final String TAG                      = AnecdoteFragment.class.getSimpleName();
    private static final String ARGS_WEBSITE_PARENT_NAME = "key_website_parent_name";
    private static final String ARGS_WEBSITE_PARENT_SLUG = "key_website_parent_slug";
    public static final  String ARGS_WEBSITE_PAGE_SLUG   = "key_website_page_slug";
    private static final String ARGS_WEBSITE_NAME        = "key_website_name";

    /**
     * Define the threshold to load new items. It's the number of items not visible after the current last visible.
     */
    public static final int PREFETECH_THRESHOLD = 4;

    @BindView(R.id.swipeRefreshLayout)
    public SwipeRefreshLayout  mSwipeRefreshLayout;
    @BindView(R.id.recyclerViewContainer)
    public EnhancedFrameLayout mRecyclerViewContainer;
    @BindView(R.id.recyclerView)
    public RecyclerView        mRecyclerView;

    private   String              mWebsiteName;
    protected String              mWebsiteSlug;
    protected String              mPageSlug;
    protected String              mWebsiteAndPageName;
    protected AnecdoteAdapter     mAdapter;
    @Nullable
    protected AnecdoteService     mAnecdoteService;
    @Inject
    protected FavoritesRepository mFavoritesRepository;

    private LinearLayoutManager mLayoutManager;
    private boolean             mIsLoadingNewItems;
    private boolean             mNoData;
    private boolean             mEverythingLoaded;
    private int                 mNextPageNumber;
    private Unbinder            mUnbinder;

    public static AnecdoteFragment newInstance(Website website, WebsitePage page) {
        AnecdoteFragment fragment = new AnecdoteFragment();
        Bundle bundle = new Bundle();
        bundle.putString(AnecdoteFragment.ARGS_WEBSITE_PARENT_NAME, website.name);
        bundle.putString(AnecdoteFragment.ARGS_WEBSITE_PARENT_SLUG, website.slug);
        bundle.putString(AnecdoteFragment.ARGS_WEBSITE_PAGE_SLUG, page.slug);
        bundle.putString(AnecdoteFragment.ARGS_WEBSITE_NAME, website.name + " " + page.name);
        fragment.setArguments(bundle);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    // Inflate the view for the fragment based on layout XML
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {

        View view = inflater.inflate(R.layout.fragment_anecdote, container, false);
        mUnbinder = ButterKnife.bind(this, view);
        return view;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mUnbinder.unbind();
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        Scope scope = Toothpick.openScopes(getActivity().getApplicationContext(), this);
        Toothpick.inject(this, scope);
        mFavoritesRepository.setup();

        mLayoutManager = new LinearLayoutManager(getActivity());

        mRecyclerView.setLayoutManager(mLayoutManager);
        mRecyclerView.addOnScrollListener(mOnScrollListener);

        mSwipeRefreshLayout.setOnRefreshListener(this);

        init();
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.main, menu);
    }

    @Override
    public void onResume() {
        super.onResume();

        EventBus.getDefault().register(this);
        EventBus.getDefault().post(new ChangeTitleEvent(this, mPageSlug, true));

        EventTracker.trackFragmentView(this, mWebsiteAndPageName, EventTracker.CONTENT_TYPE_ANECDOTE);
    }

    @Override
    public void onPause() {
        super.onPause();
        EventBus.getDefault().unregister(this);
    }


    @Override
    public String getTitle() {
        return mWebsiteName;
    }

    public String getWebsitePageSlug() {
        return mPageSlug;
    }

    protected void init() {
        if (getArguments() != null) {
            mWebsiteName = getArguments().getString(ARGS_WEBSITE_PARENT_NAME);
            mWebsiteSlug = getArguments().getString(ARGS_WEBSITE_PARENT_SLUG);
            mPageSlug = getArguments().getString(ARGS_WEBSITE_PAGE_SLUG);
            mWebsiteAndPageName = getArguments().getString(ARGS_WEBSITE_NAME);
        }

        // Get text pref
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getContext());
        int textSize = Integer.parseInt(preferences.getString(getString(R.string.pref_textsize_key), String.valueOf(getResources().getInteger(R.integer.anecdote_textsize_default))));
        boolean rowStripping = preferences.getBoolean(getString(R.string.pref_rowstriping_key), getResources().getBoolean(R.bool.pref_rowstripping_default));

        mAnecdoteService = ((MainActivity) getActivity()).getAnecdoteService(mPageSlug);

        if (mAnecdoteService == null) {
            Log.e(TAG, "Unable to get an AnecdoteService");
            return;
        }

        mAdapter = new MixedContentAdapter(this, mWebsiteAndPageName);
        mAdapter.setLoaderDisplay(!mAnecdoteService.getWebsitePage().isSinglePage);

        mAdapter.setData(mAnecdoteService.getAnecdotes());
        mRecyclerView.setAdapter((RecyclerView.Adapter) mAdapter);

        // Set default values
        mIsLoadingNewItems = false;

        int colorBackground;
        int colorBackgroundStripping;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            colorBackground = getResources().getColor(R.color.rowColorBackground, null);
            colorBackgroundStripping = getResources().getColor(R.color.rowColorBackgroundStripping, null);
        } else {
            // noinspection deprecation
            colorBackground = getResources().getColor(R.color.rowColorBackground);
            // noinspection deprecation
            colorBackgroundStripping = getResources().getColor(R.color.rowColorBackgroundStripping);
        }
        mAdapter.setTextStyle(textSize, rowStripping, colorBackground, colorBackgroundStripping);
    }

    /**
     * To be called by child fragment when a request if finished, could be an error or success.
     *
     * @param dataChanged true if the dataSet has changed, like new Anecdote is here!
     */
    protected void afterRequestFinished(boolean dataChanged) {
        mIsLoadingNewItems = false;

        if (dataChanged && mAnecdoteService != null) {
            if (mAnecdoteService.getAnecdotes().size() == 0) {
                // We don't have any data to display, we prevent any more request and display a nice friendly message.
                mNoData = true;
                mRecyclerViewContainer.displayOverlay(R.layout.overlay_nodata);
                return;
            } else {
                if (mAdapter.getContentItemCount() > 0 &&
                        mAdapter.getContentItemCount() >= mAnecdoteService.getAnecdotes().size()) {
                    mAdapter.setLoaderDisplay(false);
                    mEverythingLoaded = true;
                } else {
                    mAdapter.setLoaderDisplay(true);
                }
                mNextPageNumber++;
                mAdapter.setData(mAnecdoteService.getAnecdotes());
            }
        }

        mRecyclerViewContainer.hideOverlay();

        if (shouldLoadNewAnecdote()) {
            Log.d(TAG, "afterRequestFinished, load new anecdotes");
            loadNewAnecdotes(mNextPageNumber);
        }

    }

    /**
     * Post a new event to load a new event page
     *
     * @param page the page to load
     */
    protected void loadNewAnecdotes(int page) {
        mIsLoadingNewItems = true;
        EventBus.getDefault().post(new LoadNewAnecdoteEvent(mPageSlug, page));
    }

    /**
     * Open the given anecdote content in fullscreen if it's an image or video, else in the browser.
     *
     * @param anecdote anecdote to open
     * @param view     view to have nice transition if possible
     */
    private void fullscreenAnecdote(Anecdote anecdote, View view) {
        if (anecdote.media == null) {
            EventBus.getDefault().post(new OpenAnecdoteEvent(mWebsiteAndPageName, anecdote, false));
            return;
        }

        if (anecdote.type == null || mAnecdoteService == null) {
            Log.w(TAG, "Fullscreen not possible");
            return;
        }

        Intent intent = FullscreenActivity.createIntent(
                getContext(),
                mAnecdoteService.getAnecdotes().indexOf(anecdote),
                anecdote,
                mPageSlug,
                view);


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            startActivity(
                    intent,
                    ActivityOptions.makeSceneTransitionAnimation(
                            getActivity(),
                            view,
                            view.getTransitionName()).toBundle()
            );
        } else {
            startActivity(intent);
        }
    }


    private RecyclerView.OnScrollListener mOnScrollListener = new RecyclerView.OnScrollListener() {
        @Override
        public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
            super.onScrolled(recyclerView, dx, dy);

            // Scrolled to bottom. Do something here.
            if (shouldLoadNewAnecdote()) {
                Log.d(TAG, "Scrolled to end, load new anecdotes");
                loadNewAnecdotes(mNextPageNumber);
            }
        }
    };

    /**
     * Define the condition to preload enw anecdote before arriving at the end of the list
     *
     * @return true if should preload, false otherweise
     */
    private boolean shouldLoadNewAnecdote() {
        int totalItemCount = mAdapter.getContentItemCount();
        int lastVisibleItem = mLayoutManager.findLastVisibleItemPosition();
        boolean willPrefetch = (lastVisibleItem >= (totalItemCount - PREFETECH_THRESHOLD));

        // Scrolled to bottom. Do something here.
        if (!mIsLoadingNewItems && willPrefetch && !mNoData && !mEverythingLoaded) {
            //noinspection RedundantIfStatement
            if (mAnecdoteService != null && mAnecdoteService.getWebsitePage().isSinglePage) {
                return false;
            }
            return true;
        }
        return false;
    }

    /***************************
     * Implement SwipeRefreshLayout.OnRefreshListener
     ***************************/

    @Override
    public void onRefresh() {
        if (mAnecdoteService == null) {
            return;
        }
        mSwipeRefreshLayout.setRefreshing(false);

        // Reset the no data flag
        mNoData = mEverythingLoaded = false;
        mAnecdoteService.cleanAnecdotes();
        mAdapter.setData(new ArrayList<Anecdote>());
        loadNewAnecdotes(mNextPageNumber = 0);
    }


    /***************************
     * Implement ViewHolderListener
     **************************/

    @Override
    public void onClick(Anecdote anecdote, View view, int action) {
        switch (action) {
            default:
            case AdapterListener.ACTION_COPY:
                EventBus.getDefault().post(new CopyAnecdoteEvent(mWebsiteAndPageName, anecdote, CopyAnecdoteEvent.TYPE_ANECDOTE, anecdote.getShareString(getContext())));
                break;
            case AdapterListener.ACTION_SHARE:
                EventBus.getDefault().post(new ShareAnecdoteEvent(mWebsiteAndPageName, anecdote, anecdote.getShareString(getContext())));
                break;
            case AdapterListener.ACTION_OPEN_IN_BROWSER_PRELOAD:
                EventBus.getDefault().post(new OpenAnecdoteEvent(mWebsiteAndPageName, anecdote, true));
                break;
            case AdapterListener.ACTION_OPEN_IN_BROWSER:
                EventBus.getDefault().post(new OpenAnecdoteEvent(mWebsiteAndPageName, anecdote, false));
                break;
            case AdapterListener.ACTION_FULLSCREEN:
                fullscreenAnecdote(anecdote, view);
                break;
            case AdapterListener.ACTION_FAVORIS:
                EventBus.getDefault().post(new FavoritesEvent(mPageSlug, anecdote, !anecdote.isFavorite()));
                break;
        }
    }

    @Override
    public void onLongClick(final Object object) {
        // Nothing here
    }


    /***************************
     * Event
     ***************************/

    @Subscribe(sticky = true, threadMode = ThreadMode.MAIN)
    public void onRequestFailedEvent(RequestFailedEvent event) {
        if (event.originalEvent instanceof LoadNewAnecdoteEvent &&
                !((LoadNewAnecdoteEvent) event.originalEvent).websitePageSlug.equals(mPageSlug)) return;

        EventBus.getDefault().removeStickyEvent(event.getClass());
        afterRequestFinished(false);
    }

    @Subscribe(sticky = true, threadMode = ThreadMode.MAIN)
    public void onAnecdoteReceived(OnAnecdoteLoadedEvent event) {
        EventBus.getDefault().removeStickyEvent(event.getClass());
        afterRequestFinished(true);
    }

    @Subscribe
    public void onUpdateAnecdoteFragment(UpdateAnecdoteFragmentEvent event) {
        init();
        EventBus.getDefault().post(new ChangeTitleEvent(this, mPageSlug, true));
    }

    @Subscribe
    public void onUpdateAnecdote(UpdateAnecdoteEvent event) {
        if (mAnecdoteService != null) {
            afterRequestFinished(true);
        }
    }
}