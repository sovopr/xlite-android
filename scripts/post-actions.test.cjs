const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const html = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/index.html'), 'utf8');
const script = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)][1][1];
const requests = [];
const alerts = [];
const postText = { value: 'Slow connection post' };
const postButton = { disabled: false, innerHTML: 'Post' };
const replyText = { value: 'Slow connection reply' };
const replyButton = { disabled: false, innerHTML: 'Reply' };
const statusBar = { textContent: '', innerHTML: '', style: {}, className: '' };
const nodes = { postText, postBtn: postButton, replyText, replyBtn: replyButton, statusBar };
const context = {
    console, URL, setTimeout, clearTimeout,
    document: { getElementById(id) { return nodes[id] || null; } },
    AndroidBridge: { executePostAsync(url, payload, callbackId) { requests.push({ url, payload: JSON.parse(payload), callbackId }); } },
    alert(message) { alerts.push(message); }
};
context.window = context;
vm.createContext(context);
vm.runInContext(script, context);
vm.runInContext("nav = view => { testNav = view; }; renderThreadView = id => { testThread = id; };", context);

const event = { preventDefault() {} };
vm.runInContext("currentView = 'post'", context);
context.executePostTweet(event);
assert.equal(requests.length, 1, 'compose starts one async request');
assert.equal(postButton.disabled, true);
assert.equal(postText.value, 'Slow connection post', 'draft remains while pending');
assert.equal(requests[0].payload.variables.tweet_text, 'Slow connection post');
context.__onAsyncFinish(requests.shift().callbackId, JSON.stringify({ error: true, message: 'Network timeout' }));
assert.equal(postButton.disabled, false, 'failed post can be retried');
assert.equal(postText.value, 'Slow connection post', 'failed post keeps the draft');
assert.match(alerts.pop(), /Network timeout/);

context.executePostTweet(event);
context.__onAsyncFinish(requests.shift().callbackId, JSON.stringify({ data: { create_tweet: {} } }));
assert.equal(context.testNav, 'feed', 'successful post returns to feed');

vm.runInContext("currentView = 'post'; viewGeneration++", context);
context.executePostTweet(event);
vm.runInContext("currentView = 'search'; viewGeneration++", context);
context.__onAsyncFinish(requests.shift().callbackId, JSON.stringify({ data: { create_tweet: {} } }));
assert.equal(context.testNav, 'feed', 'late post completion does not navigate away from a new view');

vm.runInContext("currentView = 'thread'; viewGeneration++", context);
context.postReply(event, '123');
assert.equal(requests.length, 1);
assert.equal(requests[0].payload.variables.reply.in_reply_to_tweet_id, '123');
assert.equal(replyButton.disabled, true);
context.__onAsyncFinish(requests.shift().callbackId, JSON.stringify({ data: { create_tweet: {} } }));
assert.equal(context.testThread, '123', 'successful reply reloads its conversation');

delete context.AndroidBridge.executePostAsync;
context.AndroidBridge.executePost = () => JSON.stringify({ error: true, message: 'Legacy request failed' });
vm.runInContext("currentView = 'post'; viewGeneration++", context);
context.executePostTweet(event);
setTimeout(() => {
    assert.equal(postButton.disabled, false, 'old APK fallback completes on failure');
    assert.match(alerts.pop(), /Legacy request failed/);
    delete context.AndroidBridge.executePost;
    context.executePostTweet(event);
    assert.equal(postButton.disabled, false, 'missing bridge cannot leave the button spinning');
    assert.match(alerts.pop(), /Native bridge unavailable/);
    runExtendedActionTests();
}, 30);

function makeElement(attrs = {}, initialText = '') {
    const classes = new Set((attrs.class || '').split(/\s+/).filter(Boolean));
    return {
        textContent: initialText,
        innerHTML: initialText,
        focused: false,
        scrolled: false,
        scrollIntoView() { this.scrolled = true; },
        focus() { this.focused = true; },
        getAttribute(name) { return Object.prototype.hasOwnProperty.call(attrs, name) ? attrs[name] : null; },
        classList: {
            toggle(cls, force) {
                if (force === undefined) {
                    if (classes.has(cls)) { classes.delete(cls); return false; }
                    classes.add(cls); return true;
                }
                if (force) classes.add(cls); else classes.delete(cls);
                return !!force;
            },
            contains(cls) { return classes.has(cls); }
        }
    };
}

function makeFakeDoc(actionNodes, extraById = {}) {
    return {
        getElementById(id) { return extraById[id] || null; },
        querySelectorAll(selector) {
            const m = selector.match(/^\[([a-z0-9-]+)\]$/i);
            if (m) return actionNodes.filter(el => el.getAttribute(m[1]) !== null);
            if (selector === '.tweet[data-id]') return actionNodes.filter(el => el.getAttribute('data-id') !== null);
            return [];
        }
    };
}

function runExtendedActionTests() {
    // 1. Offline tab Recent Cache hydration, Save/Unsave duplicate cards, malformed cache guard
    const store = {
        cache_bookmarks: 'null',
        cache_feed_cache: JSON.stringify([
            { id: '900', text: 'Cached post', user_name: 'A', user_screen_name: 'a', likes: 5, retweets: 2, replies: 1, liked: false, retweeted: false }
        ])
    };
    const signedRequests = [];
    const mainContent = { innerHTML: '' };
    const statusBarEl = { textContent: '', innerHTML: '', style: {}, className: '' };
    const likeElA = makeElement({ 'data-like-id': '900' }, '♡ 5');
    const likeElB = makeElement({ 'data-like-id': '900' }, '♡ 5');
    const rtEl = makeElement({ 'data-rt-id': '900' }, '⇄ 2');
    const replyEl = makeElement({ 'data-reply-id': '900' }, '💬 1');
    const bmElA = makeElement({ 'data-bm-id': '900' }, '☆ Save');
    const bmElB = makeElement({ 'data-bm-id': '900' }, '☆ Save');
    const tweetCard = makeElement({ 'data-id': '900' });
    const replyInputEl = makeElement({});
    const replyBtnEl = { disabled: false, innerHTML: 'Reply' };
    const actionNodes = [tweetCard, likeElA, likeElB, rtEl, replyEl, bmElA, bmElB];
    const savedFragment = {
        firstChild: tweetCard,
        querySelectorAll(selector) {
            const m = selector.match(/^\[([a-z0-9-]+)\]$/i);
            return m ? actionNodes.filter(el => el.getAttribute(m[1]) !== null) : [];
        }
    };

    const extCtx = {
        console, URL, setTimeout, clearTimeout,
        window: null,
        navigator: { onLine: false },
        history: { state: null, pushState() {}, replaceState() {} },
        scrollY: 120,
        scrollTo() {},
        alerts: [],
        alert(msg) { this.alerts.push(msg); },
        document: makeFakeDoc(actionNodes, {
            mainContent,
            statusBar: statusBarEl,
            replyText: replyInputEl,
            replyBtn: replyBtnEl,
            tabNav: { style: {} }
        }),
        AndroidBridge: {
            getCache(k) { return store['cache_' + k] || ''; },
            saveCache(k, v) { store['cache_' + k] = String(v); },
            executePostWithTransactionAsync(url, payload, tid, callbackId) {
                signedRequests.push({ url, payload: JSON.parse(payload), tid, callbackId });
            },
            executeGetAsync() {}
        }
    };
    extCtx.window = extCtx;
    vm.createContext(extCtx);
    vm.runInContext(script, extCtx);

    // parseSingleTweet reads favorited, retweeted, bookmarked
    const parsedTweet = extCtx.parseSingleTweet({
        rest_id: '777',
        core: { user_results: { result: { core: { name: 'U', screen_name: 'u' } } } },
        legacy: {
            id_str: '777',
            full_text: 'Hello',
            favorite_count: 10,
            retweet_count: 3,
            reply_count: 1,
            favorited: true,
            retweeted: true,
            bookmarked: true
        }
    });
    assert.equal(parsedTweet.liked, true);
    assert.equal(parsedTweet.retweeted, true);
    assert.equal(parsedTweet.bookmarked, true);

    // Offline tab hydrates Recent Cache into allTweetsMap so Save works immediately
    vm.runInContext("currentView = 'feed'; currentTab = 'cache';", extCtx);
    extCtx.loadFeed('cache');
    assert.equal(mainContent.innerHTML.includes('Recent Cache'), true);
    assert.equal(mainContent.innerHTML.includes('data-bm-id="900"'), true);
    assert.equal(mainContent.innerHTML.includes('onclick="toggleLike(\'900\', event)"'), true);
    assert.equal(mainContent.innerHTML.includes('onclick="toggleRetweet(\'900\', event)"'), true);
    assert.equal(mainContent.innerHTML.includes('onclick="commentTweet(\'900\', event)"'), true);

    let stopped = false;
    extCtx.toggleBookmark('900', { stopPropagation() { stopped = true; } });
    assert.equal(stopped, true, 'Save stops event propagation');
    assert.equal(bmElA.textContent, '★ Saved');
    assert.equal(bmElB.textContent, '★ Saved', 'All matching Save buttons update');
    assert.equal(JSON.parse(store.cache_bookmarks).length, 1);
    assert.equal(mainContent.innerHTML.includes('Saved Tweets (1)'), true, 'Offline tab renders Saved Tweets immediately');
    assert.equal(statusBarEl.textContent, 'Saved for offline reading', 'Offline re-render preserves save toast');

    // Unsave refreshes Offline Saved section cleanly
    extCtx.toggleBookmark('900');
    assert.equal(JSON.parse(store.cache_bookmarks).length, 0);
    assert.equal(mainContent.innerHTML.includes('Saved Tweets'), false);
    assert.equal(statusBarEl.textContent, 'Removed from offline saved');

    // Legacy bridge without executePostWithTransactionAsync blocks Like/Retweet clearly & reverts
    delete extCtx.AndroidBridge.executePostWithTransactionAsync;
    extCtx.toggleLike('900');
    assert.equal(likeElA.textContent, '♡ 5', 'Like reverts when APK lacks signed POST bridge');
    assert.match(statusBarEl.textContent, /Update to v1\.0\.16 APK/);

    // Signed Like, Retweet, Bookmark & CreateTweet flow
    extCtx.AndroidBridge.executePostWithTransactionAsync = (url, payload, tid, callbackId) => {
        signedRequests.push({ url, payload: JSON.parse(payload), tid, callbackId });
    };
    extCtx.navigator.onLine = true;
    vm.runInContext(`
        savedFeedNodes = __frag;
        searchTransactionClient = { generateTransactionId(method, p) { return 'A'.repeat(94); } };
        searchTransactionFetchedAt = Date.now();
    `, Object.assign(extCtx, { __frag: savedFragment }));

    // Like + lock + revert on failure
    extCtx.toggleLike('900');
    assert.equal(likeElA.textContent, '♥ 6');
    assert.equal(likeElB.textContent, '♥ 6', 'Detached/duplicate node stays in sync');
    assert.equal(likeElA.classList.contains('liked'), true);
    assert.equal(signedRequests.length, 1);
    assert.match(signedRequests[0].url, /\/FavoriteTweet$/);
    assert.equal(signedRequests[0].payload.variables.tweet_id, '900');
    assert.equal(signedRequests[0].tid.length, 94);
    extCtx.toggleLike('900');
    assert.equal(signedRequests.length, 1, 'In-flight like prevents double submit');
    extCtx.__onAsyncFinish(signedRequests.shift().callbackId, JSON.stringify({ data: { favorite_tweet: 'Done' } }));

    // Unlike failure reverts state back cleanly
    extCtx.toggleLike('900');
    assert.equal(likeElA.textContent, '♡ 5');
    assert.match(signedRequests[0].url, /\/UnfavoriteTweet$/);
    extCtx.__onAsyncFinish(signedRequests.shift().callbackId, JSON.stringify({ error: true, message: 'Server 500' }));
    assert.equal(likeElA.textContent, '♥ 6', 'Failed unlike reverts back to liked');
    assert.match(statusBarEl.textContent, /Like failed: Server 500/);

    // Retweet & Unretweet uses source_tweet_id
    extCtx.toggleRetweet('900');
    assert.equal(rtEl.textContent, '⇄ 3');
    assert.equal(rtEl.classList.contains('retweeted'), true);
    assert.match(signedRequests[0].url, /\/CreateRetweet$/);
    assert.equal(signedRequests[0].payload.variables.tweet_id, '900');
    extCtx.__onAsyncFinish(signedRequests.shift().callbackId, JSON.stringify({ data: { create_retweet: { retweet_results: { result: {} } } } }));

    extCtx.toggleRetweet('900');
    assert.equal(rtEl.textContent, '⇄ 2');
    assert.match(signedRequests[0].url, /\/DeleteRetweet$/);
    assert.equal(signedRequests[0].payload.variables.source_tweet_id, '900');
    extCtx.__onAsyncFinish(signedRequests.shift().callbackId, JSON.stringify({ data: { unretweet: { source_tweet_results: {} } } }));

    // Save when online triggers CreateBookmark too without blocking offline save
    extCtx.toggleBookmark('900');
    assert.equal(bmElA.textContent, '★ Saved');
    assert.equal(signedRequests.length, 1);
    assert.match(signedRequests[0].url, /\/CreateBookmark$/);
    extCtx.__onAsyncFinish(signedRequests.shift().callbackId, JSON.stringify({ error: true, message: 'Offline' }));
    assert.equal(extCtx.isBookmarked('900'), true, 'Failed remote bookmark sync never drops local save');

    // commentTweet on active thread focuses reply box without reloading
    vm.runInContext("currentView = 'thread'; activeThreadTweetId = '900';", extCtx);
    extCtx.commentTweet('900', { stopPropagation() {} });
    assert.equal(replyInputEl.focused, true, 'Tapping focal comment icon focuses reply box');

    // Signed CreateTweet reply uses new queryId + falls back on 404 + updates reply count
    replyInputEl.value = 'Nice post';
    extCtx.postReply({ preventDefault() {} }, '900');
    assert.equal(signedRequests.length, 1);
    assert.match(signedRequests[0].url, /\/l4hk8uVGvR-a8kzQ4nOhPA\/CreateTweet$/);
    assert.equal(signedRequests[0].payload.features.rweb_conversational_replies_downvote_enabled, false);
    assert.equal(signedRequests[0].payload.features.responsive_web_made_on_x_label_enabled, false);
    extCtx.__onAsyncFinish(signedRequests.shift().callbackId, JSON.stringify({ error: true, status: 404, message: 'HTTP 404 from X' }));
    assert.equal(signedRequests.length, 1, '404 retries fallback CreateTweet queryId');
    assert.match(signedRequests[0].url, /\/wR5Z_lEibLvkew3tF9w-Sg\/CreateTweet$/);
    vm.runInContext("renderThreadView = () => {};", extCtx);
    extCtx.__onAsyncFinish(signedRequests.shift().callbackId, JSON.stringify({ data: { create_tweet: { tweet_results: { result: {} } } } }));
    assert.equal(replyEl.textContent, '💬 2', 'Successful reply increments parent reply count');

    // compareSemver, Settings APK update banner, and downloadApkUpdate bridge delegation
    assert.equal(extCtx.compareSemver('1.0.17', '1.0.16') > 0, true);
    assert.equal(extCtx.compareSemver('1.0.17', '1.0.17'), 0);
    assert.equal(extCtx.compareSemver('1.0.9', '1.0.17') < 0, true);
    assert.equal(extCtx.compareSemver('unknown', '1.0.17'), 0);

    let mockInstalledApk = '1.0.16';
    const openedUrls = [];
    extCtx.AndroidBridge.getCookies = () => JSON.stringify({ auth_token: 'tok', ct0: 'csrf', image_mode: 'tap' });
    extCtx.AndroidBridge.isUsingLiveHtml = () => false;
    extCtx.AndroidBridge.getAppVersionInfo = () => JSON.stringify({ version: mockInstalledApk, build: '15', isLive: false });
    extCtx.AndroidBridge.openExternalUrl = url => { openedUrls.push(url); return true; };

    extCtx.renderSettings();
    assert.match(mainContent.innerHTML, /Native APK Update Available/);
    assert.match(mainContent.innerHTML, /Download APK Update/);

    extCtx.downloadApkUpdate();
    assert.equal(openedUrls.length, 1);
    assert.match(openedUrls[0], /\/releases\/latest\/download\/xlite-fup-release\.apk$/);

    mockInstalledApk = vm.runInContext('LATEST_APK_VERSION', extCtx);
    extCtx.renderSettings();
    assert.doesNotMatch(mainContent.innerHTML, /Native APK Update Available/, 'Settings hides APK update prompt when current APK matches LATEST_APK_VERSION');

    // Unified in-app full update (v1.0.18+ native APK + web UI from one Update button)
    const updateStatusEl = { textContent: '', innerHTML: '' };
    const updateBtnEl = { disabled: false, textContent: 'Check & Install Updates' };
    const apkUpdateBtnEl = { disabled: false, textContent: 'Download APK Update' };
    const cancelApkBtnEl = { style: { display: 'none' } };
    const browserFallbackBtnEl = { style: { display: 'none' }, textContent: '' };
    const reloadWebBtnEl = { style: { display: 'none' }, textContent: '' };
    const originalGetById = extCtx.document.getElementById;
    extCtx.document.getElementById = id => {
        if (id === 'updateStatusText') return updateStatusEl;
        if (id === 'updateBtn') return updateBtnEl;
        if (id === 'apkUpdateBtn') return apkUpdateBtnEl;
        if (id === 'cancelApkUpdateBtn') return cancelApkBtnEl;
        if (id === 'apkBrowserFallbackBtn') return browserFallbackBtnEl;
        if (id === 'reloadWebUpdateBtn') return reloadWebBtnEl;
        return originalGetById(id);
    };

    const apkInstalls = [];
    const savedHtmls = [];
    let reloadCount = 0;
    extCtx.AndroidBridge.installApkUpdateAsync = (url, ver) => {
        apkInstalls.push({ url, ver });
        return true;
    };
    extCtx.AndroidBridge.saveLiveHtml = html => {
        savedHtmls.push(html);
        return true;
    };
    extCtx.AndroidBridge.reloadApp = () => { reloadCount++; };
    extCtx.AndroidBridge.getApkUpdateStatus = () => JSON.stringify({
        downloading: false,
        canInstall: true,
        cachedVersion: vm.runInContext('LATEST_APK_VERSION', extCtx)
    });

    mockInstalledApk = '1.0.17';
    extCtx.renderSettings();
    assert.match(mainContent.innerHTML, /Check &amp; Install Updates/);
    assert.match(mainContent.innerHTML, /Install Ready Update/);

    // Remote has BOTH newer web and newer APK -> saves live HTML quietly and launches in-app APK download immediately
    mockInstalledApk = vm.runInContext('LATEST_APK_VERSION', extCtx);
    const fullRemoteHtml = [
        '<!DOCTYPE html><html><body><div id="mainContent"></div><script>',
        "const APP_VERSION = '9.9.9';",
        "const LATEST_APK_VERSION = '9.9.9';",
        "const LATEST_APK_URL = 'https://github.com/sovopr/xlite-android/releases/latest/download/xlite-fup-release.apk';",
        '</script></body></html>'
    ].join('\n');
    extCtx.applyUpdate(fullRemoteHtml);
    assert.equal(savedHtmls.length, 1, 'saves live HTML before native update without disrupting progress UI');
    assert.equal(reloadCount, 0, 'does not reload WebView mid-APK download');
    assert.equal(apkInstalls.length, 1, 'single Update flow starts native APK download automatically');
    assert.equal(apkInstalls[0].url, 'https://github.com/sovopr/xlite-android/releases/download/v9.9.9/xlite-fup-release.apk');
    assert.equal(apkInstalls[0].ver, '9.9.9');

    // Progress, unknown-sources permission pause, installer launch, and error events drive UI cleanly
    extCtx.__onApkUpdateEvent(JSON.stringify({
        state: 'downloading',
        version: '9.9.9',
        bytes: 2 * 1024 * 1024,
        totalBytes: 4 * 1024 * 1024,
        percent: 50
    }));
    assert.match(updateStatusEl.textContent, /Downloading full update v9\.9\.9.*50%.*2\.0 MB \/ 4\.0 MB/);
    assert.equal(updateBtnEl.disabled, true);
    assert.equal(cancelApkBtnEl.style.display, 'block');

    extCtx.__onApkUpdateEvent(JSON.stringify({
        state: 'permission_required',
        version: '9.9.9',
        message: "Enable 'Allow from this source'"
    }));
    assert.equal(updateBtnEl.disabled, false);
    assert.equal(updateBtnEl.textContent, 'Install Ready Update (v9.9.9)');
    assert.equal(cancelApkBtnEl.style.display, 'none');

    extCtx.__onApkUpdateEvent(JSON.stringify({ state: 'installing', version: '9.9.9' }));
    assert.match(updateStatusEl.textContent, /Opening Android installer for v9\.9\.9/);
    assert.equal(updateBtnEl.textContent, 'Check & Install Updates');

    extCtx.__onApkUpdateEvent(JSON.stringify({ state: 'error', message: 'Transfer stalled' }));
    assert.equal(updateStatusEl.textContent, 'Transfer stalled');
    assert.equal(updateBtnEl.disabled, false);

    // Web-only update (same APK version) applies OTA reload without APK download
    apkInstalls.length = 0;
    const webOnlyHtml = [
        '<!DOCTYPE html><html><body><div id="mainContent"></div><script>',
        "const APP_VERSION = '9.9.9';",
        `const LATEST_APK_VERSION = '${vm.runInContext('LATEST_APK_VERSION', extCtx)}';`,
        '</script></body></html>'
    ].join('\n');
    extCtx.applyUpdate(webOnlyHtml);
    assert.equal(apkInstalls.length, 0, 'web-only update skips APK download');
    assert.equal(savedHtmls.length, 2);

    // Private-repo HTTP 404 during APK update after saving Web UI exposes browser fallback + Web UI reload buttons
    extCtx.applyUpdate(fullRemoteHtml);
    extCtx.__onApkUpdateEvent(JSON.stringify({
        state: 'error',
        version: '9.9.9',
        code: 404,
        message: 'HTTP 404 downloading APK'
    }));
    assert.match(updateStatusEl.textContent, /HTTP 404.*private GitHub release/i);
    assert.match(updateStatusEl.textContent, /Web UI v9\.9\.9 is ready/i);
    assert.equal(browserFallbackBtnEl.style.display, 'block');
    assert.equal(browserFallbackBtnEl.textContent, 'Open Download in Browser (v9.9.9)');
    assert.equal(reloadWebBtnEl.style.display, 'block');
    assert.equal(reloadWebBtnEl.textContent, 'Apply Saved Web UI (v9.9.9)');

    openedUrls.length = 0;
    extCtx.openApkDownloadInBrowser();
    assert.equal(openedUrls.length, 1);
    assert.equal(openedUrls[0], 'https://github.com/sovopr/xlite-android/releases/download/v9.9.9/xlite-fup-release.apk');

    const prevReloads = reloadCount;
    extCtx.reloadSavedWebUpdate();
    assert.equal(reloadCount, prevReloads + 1);

    // Verified APK updater uses installApkUpdateVerifiedAsync with parsed LATEST_APK_SHA256 when exposed by bridge
    const verifiedCalls = [];
    const expectedSha = '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef';
    extCtx.AndroidBridge.installApkUpdateVerifiedAsync = (url, ver, sha) => {
        verifiedCalls.push({ url, ver, sha });
        return true;
    };
    const verifiedRemoteHtml = [
        '<!DOCTYPE html><html><body><div id="mainContent"></div><script>',
        "const APP_VERSION = '9.9.9';",
        "const LATEST_APK_VERSION = '9.9.9';",
        "const LATEST_APK_URL = 'https://github.com/sovopr/xlite-android/releases/latest/download/xlite-fup-release.apk';",
        `const LATEST_APK_SHA256 = '${expectedSha}';`,
        '</script></body></html>'
    ].join('\n');
    apkInstalls.length = 0;
    extCtx.applyUpdate(verifiedRemoteHtml);
    assert.equal(verifiedCalls.length, 1);
    assert.equal(verifiedCalls[0].url, 'https://github.com/sovopr/xlite-android/releases/download/v9.9.9/xlite-fup-release.apk');
    assert.equal(verifiedCalls[0].ver, '9.9.9');
    assert.equal(verifiedCalls[0].sha, expectedSha);
    assert.equal(apkInstalls.length, 0, 'does not call legacy installer when verified installer handles update');
    delete extCtx.AndroidBridge.installApkUpdateVerifiedAsync;

    // Settings renders Profile Pictures, Hide Ads & Seen Sync preferences and saveSettings persists them
    store.cache_pref_avatar_mode = 'normal';
    store.cache_pref_hide_ads = 'false';
    store.cache_pref_sync_seen = 'false';
    extCtx.renderSettings();
    assert.match(mainContent.innerHTML, /Profile Pictures/);
    assert.match(mainContent.innerHTML, /name="avatarMode" value="normal" checked/);
    assert.match(mainContent.innerHTML, /Promoted Posts \(Ads\)/);
    assert.match(mainContent.innerHTML, /name="hideAds" value="false" checked/);
    assert.match(mainContent.innerHTML, /Sync Seen Posts to X/);
    assert.match(mainContent.innerHTML, /name="syncSeen" value="false" checked/);

    let savedConfigArgs = null;
    let cacheClearedCount = 0;
    extCtx.alert = msg => extCtx.alerts.push(msg);
    extCtx.AndroidBridge.clearCache = () => { cacheClearedCount++; };
    extCtx.AndroidBridge.saveConfig = (a, c, m) => { savedConfigArgs = { a, c, m }; };
    extCtx.document.getElementById = id => {
        if (id === 'cfgAuth') return { value: 'tok' };
        if (id === 'cfgCt0') return { value: 'csrf' };
        return originalGetById(id);
    };
    extCtx.document.querySelector = sel => {
        if (sel === 'input[name="imgMode"]:checked') return { value: 'tap' };
        if (sel === 'input[name="avatarMode"]:checked') return { value: 'mini' };
        if (sel === 'input[name="hideAds"]:checked') return { value: 'true' };
        if (sel === 'input[name="syncSeen"]:checked') return { value: 'true' };
        return null;
    };
    vm.runInContext("nav = () => {};", extCtx);
    extCtx.saveSettings({ preventDefault() {} });
    assert.equal(store.cache_pref_avatar_mode, 'mini');
    assert.equal(store.cache_pref_hide_ads, 'true');
    assert.equal(store.cache_pref_sync_seen, 'true');
    assert.deepEqual(savedConfigArgs, { a: 'tok', c: 'csrf', m: 'tap' });

    // __onAsyncFinish prefers takeTempResult when directResult is omitted
    const tempTakes = [];
    extCtx.AndroidBridge.takeTempResult = cbId => {
        tempTakes.push(cbId);
        return '{"ok":true,"via":"takeTempResult"}';
    };
    let takenResult = '';
    const asyncFinish = extCtx.window.__onAsyncFinish || extCtx.__onAsyncFinish;
    vm.runInContext("pendingCallbacks['cb_temp_test'] = (r) => { globalThis.__takenOut = r; };", extCtx);
    asyncFinish('cb_temp_test');
    assert.deepEqual(tempTakes, ['cb_temp_test']);
    assert.equal(vm.runInContext('globalThis.__takenOut', extCtx), '{"ok":true,"via":"takeTempResult"}');

    // Quote compose includes attachment_url pointing to quoted status
    const qVars = extCtx.buildTweetVariables('My take', null, { id: '555111', screenName: 'alice' });
    assert.equal(qVars.attachment_url, 'https://x.com/alice/status/555111');

    // Profile parser reads modern X UserByScreenName fields & Follow toggles friendships endpoint
    const parsedProf = extCtx.parseUserProfileResult(JSON.stringify({
        data: { user: { result: {
            rest_id: '42',
            is_blue_verified: true,
            core: { name: 'Grace', screen_name: 'grace' },
            avatar: { image_url: 'https://pbs.twimg.com/profile_images/1/a_normal.jpg' },
            profile_bio: { description: 'Builder' },
            relationship_counts: { followers: 1200, following: 50 },
            relationship_perspectives: { following: false, followed_by: true },
            tweet_counts: { tweets: 99 }
        } } }
    }));
    assert.equal(parsedProf.id, '42');
    assert.equal(parsedProf.followers, 1200);
    assert.equal(parsedProf.verified, true);
    const followCalls = [];
    extCtx.AndroidBridge.executeFormPostWithTransactionAsync = (url, body, tid, cbId) => {
        followCalls.push({ url, body, cbId });
    };
    vm.runInContext("searchTransactionClient = { generateTransactionId: () => 'tid1' }; searchTransactionFetchedAt = Date.now();", extCtx);
    extCtx.__tmpProf = parsedProf;
    vm.runInContext("profileState = { screenName: 'grace', user: __tmpProf, tab: 'tweets', tweets: [], cursor: null, loading: false, error: '' };", extCtx);
    extCtx.toggleFollowUser('42');
    assert.equal(followCalls.length, 1);
    assert.match(followCalls[0].url, /\/i\/api\/1\.1\/friendships\/create\.json$/);
    assert.equal(followCalls[0].body, 'user_id=42');

    // Notifications fetches signed endpoint and renders clickable alerts
    const notifGets = [];
    const notifBox = { innerHTML: '' };
    const getWithNotif = extCtx.document.getElementById;
    extCtx.document.getElementById = id => (id === 'notifListBox' ? notifBox : getWithNotif(id));
    extCtx.AndroidBridge.executeSignedGetAsync = (url, tid, cbId) => notifGets.push({ url, tid, cbId });
    vm.runInContext("currentView = 'notifications';", extCtx);
    extCtx.renderNotifications();
    assert.equal(notifGets.length, 1);
    assert.match(notifGets[0].url, /\/i\/api\/2\/notifications\/all\.json/);
    asyncFinish(notifGets[0].cbId, JSON.stringify({
        globalObjects: {
            users: { '9': { id_str: '9', name: 'Bob', screen_name: 'bob' } },
            tweets: { '888': { id_str: '888', full_text: 'Original tweet text', user_id_str: '9' } },
            notifications: {
                'n1': {
                    timestampMs: '1700000000000',
                    icon: { id: 'heart_icon' },
                    message: { text: 'Bob liked your post' },
                    template: { aggregateUserActionsV1: { fromUsers: [{ user: { id: '9' } }], targetObjects: [{ tweet: { id: '888' } }] } }
                }
            }
        }
    }));
    assert.match(notifBox.innerHTML, /Bob liked your post/);
    assert.match(notifBox.innerHTML, /Original tweet text/);
    vm.runInContext("if (seenSaveDebounce) clearTimeout(seenSaveDebounce); if (seenFlushTimer) clearTimeout(seenFlushTimer);", extCtx);

    console.log('Async post and reply tests passed.');
}
