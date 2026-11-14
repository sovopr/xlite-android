const assert = require('node:assert/strict');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const http = require('node:http');
const os = require('node:os');
const path = require('node:path');

const CHROME_CANDIDATES = [
    process.env.CHROME_BIN,
    '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    '/usr/bin/google-chrome',
    '/usr/bin/chromium',
    '/usr/bin/chromium-browser'
].filter(Boolean);

const CHROME_BIN = CHROME_CANDIDATES.find(p => fs.existsSync(p));
if (!CHROME_BIN) {
    console.log('SKIP: Chrome binary not found (set CHROME_BIN to run Chrome E2E tests).');
    process.exit(0);
}

const PORTRAIT_MP4_B64 = 'AAAAIGZ0eXBpc29tAAACAGlzb21pc28yYXZjMW1wNDEAAAMPbW9vdgAAAGxtdmhkAAAAAAAAAAAAAAAAAAAD6AAAAfQAAQAAAQAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAgAAAjp0cmFrAAAAXHRraGQAAAADAAAAAAAAAAAAAAABAAAAAAAAAfQAAAAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAAEAAAABwAAAAAAAkZWR0cwAAABxlbHN0AAAAAAAAAAEAAAH0AAAAAAABAAAAAAGybWRpYQAAACBtZGhkAAAAAAAAAAAAAAAAAABAAAAAIABVxAAAAAAALWhkbHIAAAAAAAAAAHZpZGUAAAAAAAAAAAAAAABWaWRlb0hhbmRsZXIAAAABXW1pbmYAAAAUdm1oZAAAAAEAAAAAAAAAAAAAACRkaW5mAAAAHGRyZWYAAAAAAAAAAQAAAAx1cmwgAAAAAQAAAR1zdGJsAAAAuXN0c2QAAAAAAAAAAQAAAKlhdmMxAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAAEAAcABIAAAASAAAAAAAAAABFUxhdmM2Mi4xMS4xMDAgbGlieDI2NAAAAAAAAAAAAAAAGP//AAAAL2F2Y0MBQsAK/+EAF2dCwArZBD7ARAAAAwAEAAADABA8SJkgAQAFaMuDyyAAAAAQcGFzcAAAAAEAAAABAAAAFGJ0cnQAAAAAAAAqEAAAAAAAAAAYc3R0cwAAAAAAAAABAAAAAQAAIAAAAAAcc3RzYwAAAAAAAAABAAAAAQAAAAEAAAABAAAAFHN0c3oAAAAAAAACoQAAAAEAAAAUc3RjbwAAAAAAAAABAAADPwAAAGF1ZHRhAAAAWW1ldGEAAAAAAAAAIWhkbHIAAAAAAAAAAG1kaXJhcHBsAAAAAAAAAAAAAAAALGlsc3QAAAAkqXRvbwAAABxkYXRhAAAAAQAAAABMYXZmNjIuMy4xMDAAAAAIZnJlZQAAAqltZGF0AAACcAYF//9s3EXpvebZSLeWLNgg2SPu73gyNjQgLSBjb3JlIDE2NCByMzA5NSBiYWVlNDAwIC0gSC4yNjQvTVBFRy00IEFWQyBjb2RlYyAtIENvcHlsZWZ0IDIwMDMtMjAyMiAtIGh0dHA6Ly93d3cudmlkZW9sYW4ub3JnL3gyNjQuaHRtbCAtIG9wdGlvbnM6IGNhYmFjPTAgcmVmPTMgZGVibG9jaz0xOjA6MCBhbmFseXNlPTB4MToweDExMSBtZT1oZXggc3VibWU9NyBwc3k9MSBwc3lfcmQ9MS4wMDowLjAwIG1peGVkX3JlZj0xIG1lX3JhbmdlPTE2IGNocm9tYV9tZT0xIHRyZWxsaXM9MSA4eDhkY3Q9MCBjcW09MCBkZWFkem9uZT0yMSwxMSBmYXN0X3Bza2lwPTEgY2hyb21hX3FwX29mZnNldD0tMiB0aHJlYWRzPTMgbG9va2FoZWFkX3RocmVhZHM9MSBzbGljZWRfdGhyZWFkcz0wIG5yPTAgZGVjaW1hdGU9MSBpbnRlcmxhY2VkPTAgYmx1cmF5X2NvbXBhdD0wIGNvbnN0cmFpbmVkX2ludHJhPTAgYmZyYW1lcz0wIHdlaWdodHA9MCBrZXlpbnQ9MjUwIGtleWludF9taW49MiBzY2VuZWN1dD00MCBpbnRyYV9yZWZyZXNoPTAgcmNfbG9va2FoZWFkPTQwIHJjPWNyZiBtYnRyZWU9MSBjcmY9MjMuMCBxY29tcD0wLjYwIHFwbWluPTAgcXBtYXg9NjkgcXBzdGVwPTQgaXBfcmF0aW89MS40MCBhcT0xOjEuMDAAgAAAAClliIQEvEYoAAzExwABfWjgACJDJycnXXXXXXXXXXXXXXXXXXXXXXXXgA==';
const PORTRAIT_MP4_BUF = Buffer.from(PORTRAIT_MP4_B64, 'base64');

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

function makeSvgBase64(w, h, label, color = '#1d9bf0') {
    const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}"><rect width="${w}" height="${h}" fill="#16181c"/><rect x="8" y="8" width="${w - 16}" height="${h - 16}" rx="12" fill="${color}" fill-opacity="0.25" stroke="${color}" stroke-width="3"/><text x="50%" y="50%" dominant-baseline="middle" text-anchor="middle" fill="#e7e9ea" font-family="sans-serif" font-size="22">${label}</text></svg>`;
    return Buffer.from(svg, 'utf8').toString('base64');
}

function makeGraphqlTweet(id, { user = 'alice', name = 'Alice', text = 'Post ' + id, photo = null, card = null, video = null, inReplyTo = null } = {}) {
    const legacy = {
        id_str: String(id),
        full_text: text,
        created_at: 'Fri Nov 14 10:00:00 +0000 2026',
        favorite_count: 10,
        retweet_count: 2,
        reply_count: 3,
        quote_count: 0,
        bookmark_count: 1,
        favorited: false,
        retweeted: false,
        bookmarked: false
    };
    if (inReplyTo) legacy.in_reply_to_status_id_str = String(inReplyTo);
    if (photo || video) {
        const mediaItem = video ? {
            type: 'video',
            media_url_https: `https://pbs.twimg.com/ext_tw_video_thumb/${id}/pu/img/thumb.jpg`,
            ...(video.withDims ? { original_info: { width: 180, height: 320 } } : {}),
            video_info: {
                duration_millis: 4000,
                variants: [{ bitrate: 832000, content_type: 'video/mp4', url: `https://video.twimg.com/ext_tw_video/${id}/vid.mp4` }]
            }
        } : {
            type: 'photo',
            media_url_https: `https://pbs.twimg.com/media/${id}.jpg`,
            ...(photo.withDims ? { original_info: { width: photo.w || 400, height: photo.h || 300 } } : {})
        };
        legacy.extended_entities = { media: [mediaItem] };
    }
    const result = {
        __typename: 'Tweet',
        rest_id: String(id),
        core: {
            user_results: {
                result: {
                    __typename: 'User',
                    rest_id: 'u_' + user,
                    is_blue_verified: true,
                    legacy: {
                        name,
                        screen_name: user,
                        profile_image_url_https: `https://pbs.twimg.com/profile_images/${user}_normal.jpg`
                    }
                }
            }
        },
        legacy
    };
    if (card) {
        result.card = {
            rest_id: 'card_' + id,
            legacy: {
                name: 'summary_large_image',
                binding_values: [
                    { key: 'title', value: { type: 'STRING', string_value: card.title || 'Card ' + id } },
                    { key: 'description', value: { type: 'STRING', string_value: 'Card description for ' + id } },
                    { key: 'domain', value: { type: 'STRING', string_value: 'example.com' } },
                    { key: 'card_url', value: { type: 'STRING', string_value: 'https://example.com/' + id } },
                    ...(card.thumb ? [{ key: 'thumbnail_image_large', value: { type: 'IMAGE', image_value: { url: card.thumb } } }] : [])
                ]
            }
        };
    }
    return result;
}

function timelineEntry(tweetResult, sortIndex) {
    return {
        entryId: 'tweet-' + tweetResult.rest_id,
        sortIndex: String(sortIndex),
        content: {
            entryType: 'TimelineTimelineItem',
            itemContent: {
                itemType: 'TimelineTweet',
                tweet_results: { result: tweetResult }
            }
        }
    };
}

class CDPClient {
    constructor(wsUrl) {
        this.ws = new WebSocket(wsUrl);
        this.nextId = 1;
        this.pending = new Map();
        this.listeners = new Map();
        this.ready = new Promise((resolve, reject) => {
            this.ws.addEventListener('open', resolve, { once: true });
            this.ws.addEventListener('error', reject, { once: true });
        });
        this.ws.addEventListener('message', (ev) => {
            const msg = JSON.parse(ev.data);
            if (msg.id) {
                const entry = this.pending.get(msg.id);
                if (!entry) return;
                this.pending.delete(msg.id);
                if (msg.error) entry.reject(new Error(msg.error.message || JSON.stringify(msg.error)));
                else entry.resolve(msg.result);
            } else if (msg.method) {
                const set = this.listeners.get(msg.method);
                if (set) set.forEach(fn => fn(msg.params, msg.sessionId));
            }
        });
    }

    on(method, fn) {
        if (!this.listeners.has(method)) this.listeners.set(method, new Set());
        this.listeners.get(method).add(fn);
    }

    send(method, params = {}, sessionId = undefined) {
        const id = this.nextId++;
        const payload = { id, method, params };
        if (sessionId) payload.sessionId = sessionId;
        return new Promise((resolve, reject) => {
            this.pending.set(id, { resolve, reject });
            this.ws.send(JSON.stringify(payload));
        });
    }

    close() {
        try { this.ws.close(); } catch (_) {}
    }
}

async function runTests() {
    const htmlPath = path.join(__dirname, '../android/app/src/main/assets/index.html');
    const htmlContent = fs.readFileSync(htmlPath, 'utf8');

    const server = http.createServer((req, res) => {
        if (req.url === '/' || req.url.startsWith('/index.html')) {
            res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
            res.end(htmlContent);
            return;
        }
        res.writeHead(404);
        res.end('not found');
    });

    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    const port = server.address().port;
    const baseUrl = `http://127.0.0.1:${port}/index.html`;

    const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'xlite-chrome-e2e-'));
    const chrome = spawn(CHROME_BIN, [
        '--headless=new',
        '--disable-gpu',
        '--no-first-run',
        '--no-default-browser-check',
        '--remote-debugging-port=0',
        '--window-size=360,800',
        '--user-data-dir=' + userDataDir,
        'about:blank'
    ], { stdio: ['ignore', 'ignore', 'pipe'] });

    let cdp = null;
    try {
        const wsEndpoint = await new Promise((resolve, reject) => {
            let stderr = '';
            const timer = setTimeout(() => reject(new Error('Chrome startup timeout: ' + stderr)), 12000);
            chrome.stderr.on('data', chunk => {
                stderr += chunk.toString();
                const match = stderr.match(/DevTools listening on (ws:\/\/[^\s]+)/);
                if (match) {
                    clearTimeout(timer);
                    resolve(match[1]);
                }
            });
        });

        cdp = new CDPClient(wsEndpoint);
        await cdp.ready;

        const targets = await cdp.send('Target.getTargets');
        let pageTarget = targets.targetInfos.find(t => t.type === 'page');
        if (!pageTarget) {
            const created = await cdp.send('Target.createTarget', { url: 'about:blank' });
            pageTarget = { targetId: created.targetId };
        }
        const { sessionId } = await cdp.send('Target.attachToTarget', { targetId: pageTarget.targetId, flatten: true });

        await cdp.send('Page.enable', {}, sessionId);
        await cdp.send('Runtime.enable', {}, sessionId);
        await cdp.send('DOM.enable', {}, sessionId);
        await cdp.send('Emulation.setDeviceMetricsOverride', {
            width: 360,
            height: 800,
            deviceScaleFactor: 2,
            mobile: true
        }, sessionId);

        // Configure delayed responses and local proxy interception via CDP Fetch
        let slowDelayMs = 60;
        let forceDelayedMedia = false;

        await cdp.send('Fetch.enable', {
            patterns: [
                { urlPattern: 'https://local.xlite/*' },
                { urlPattern: 'http://127.0.0.1:49999/*' }
            ]
        }, sessionId);

        cdp.on('Fetch.requestPaused', async (params) => {
            const reqUrl = params.request.url;
            if (reqUrl.startsWith('https://local.xlite/img')) {
                const parsed = new URL(reqUrl);
                const orig = parsed.searchParams.get('url') || '';
                const isDelayed = forceDelayedMedia || orig.includes('slow_') || orig.includes('card_');
                const waitMs = isDelayed ? slowDelayMs : 15;
                await sleep(waitMs);
                const isAvatar = orig.includes('profile_images');
                const isCard = orig.includes('card_');
                const body = isAvatar
                    ? makeSvgBase64(96, 96, 'PFP', '#7856ff')
                    : (isCard ? makeSvgBase64(600, 340, 'CARD', '#00ba7c') : makeSvgBase64(360, 480, 'IMG', '#1d9bf0'));
                try {
                    await cdp.send('Fetch.fulfillRequest', {
                        requestId: params.requestId,
                        responseCode: 200,
                        responseHeaders: [
                            { name: 'Content-Type', value: 'image/svg+xml' },
                            { name: 'Cache-Control', value: forceDelayedMedia ? 'no-store' : 'public, max-age=60' }
                        ],
                        body
                    }, sessionId);
                } catch (_) {}
                return;
            }
            if (reqUrl.startsWith('http://127.0.0.1:49999/vid')) {
                await sleep(forceDelayedMedia ? slowDelayMs : 25);
                const total = PORTRAIT_MP4_BUF.length;
                const rangeHeader = params.request.headers?.Range || params.request.headers?.range || '';
                const rangeMatch = String(rangeHeader).match(/bytes=(\d+)-(\d*)/i);
                let code = 200;
                let slice = PORTRAIT_MP4_BUF;
                const headers = [
                    { name: 'Content-Type', value: 'video/mp4' },
                    { name: 'Accept-Ranges', value: 'bytes' },
                    { name: 'Access-Control-Allow-Origin', value: '*' }
                ];
                if (rangeMatch) {
                    const start = Math.max(0, Number(rangeMatch[1]) || 0);
                    const end = rangeMatch[2] ? Math.min(total - 1, Number(rangeMatch[2])) : total - 1;
                    code = 206;
                    slice = PORTRAIT_MP4_BUF.subarray(start, end + 1);
                    headers.push({ name: 'Content-Range', value: `bytes ${start}-${end}/${total}` });
                }
                headers.push({ name: 'Content-Length', value: String(slice.length) });
                try {
                    await cdp.send('Fetch.fulfillRequest', {
                        requestId: params.requestId,
                        responseCode: code,
                        responseHeaders: headers,
                        body: slice.toString('base64')
                    }, sessionId);
                } catch (_) {}
                return;
            }
            try { await cdp.send('Fetch.continueRequest', { requestId: params.requestId }, sessionId); } catch (_) {}
        });

        // Inject mock AndroidBridge + fixtures before any page script executes
        await cdp.send('Page.addScriptToEvaluateOnNewDocument', {
            source: `
                window.__e2e = {
                    forYouRequests: 0,
                    followingRequests: 0,
                    profileRequests: [],
                    searchRequests: [],
                    cacheStore: {
                        pref_image_mode: 'auto',
                        pref_avatar_mode: 'rich',
                        pref_hide_ads: 'true',
                        pref_autoplay_videos: 'true',
                        pref_sync_seen: 'true',
                        bookmarks: '[]',
                        feed_cache: ''
                    }
                };
                window.AndroidBridge = {
                    getCookies() {
                        return JSON.stringify({
                            auth_token: 'tok_e2e',
                            ct0: 'ct0_e2e',
                            image_mode: window.__e2e.cacheStore.pref_image_mode || 'auto'
                        });
                    },
                    getCache(k) { return window.__e2e.cacheStore[k] || ''; },
                    saveCache(k, v) { window.__e2e.cacheStore[k] = String(v ?? ''); },
                    saveConfig(a, c, m) { window.__e2e.cacheStore.pref_image_mode = m; },
                    saveImageMode(m) { window.__e2e.cacheStore.pref_image_mode = m; },
                    getVideoProxyPort() { return 49999; },
                    prefetchVideo() {},
                    getAppVersionInfo() { return JSON.stringify({ versionName: '1.0.33', versionCode: 29, build: 'e2e' }); },
                    getConnectionProfile() { return 'fast'; },
                    isUsingLiveHtml() { return false; },
                    getApkUpdateStatus() { return JSON.stringify({ state: 'idle' }); },
                    executeGetAsync(url, cbId) { window.__e2eDispatch(url, '', cbId); },
                    executeSearchGetAsync(url, _tid, cbId) { window.__e2eDispatch(url, '', cbId); },
                    executeSignedGetAsync(url, _tid, cbId) { window.__e2eDispatch(url, '', cbId); },
                    executePostAsync(url, body, cbId) { window.__e2eDispatch(url, body, cbId); },
                    executePostWithTransactionAsync(url, body, _tid, cbId) { window.__e2eDispatch(url, body, cbId); }
                };

                window.addEventListener('DOMContentLoaded', () => {
                    if (window.XClientTransaction) {
                        window.XClientTransaction.ClientTransaction = class {
                            generateTransactionId() { return 'e2e-tx'; }
                        };
                    }
                });
            `
        }, sessionId);

        const evalExpr = async (expression) => {
            const out = await cdp.send('Runtime.evaluate', {
                expression,
                returnByValue: true,
                awaitPromise: true
            }, sessionId);
            if (out.exceptionDetails) {
                const msg = out.exceptionDetails.exception?.description || out.exceptionDetails.text || 'Evaluation failed';
                throw new Error(msg);
            }
            return out.result?.value;
        };

        await cdp.send('Page.navigate', { url: baseUrl }, sessionId);
        await sleep(300);

        // Install rich fixture dispatcher + ensure warm XClientTransaction
        const makeFixtureScript = () => {
            const forYouTweets1 = [];
            for (let i = 1; i <= 14; i++) {
                const id = String(1000 + i);
                if (i % 3 === 0) {
                    forYouTweets1.push(makeGraphqlTweet(id, {
                        user: i === 9 ? 'alice' : 'user' + i,
                        name: i === 9 ? 'Alice' : 'User ' + i,
                        text: `Tweet ${id} with delayed card image and multi-line copy to make cards tall and realistic on a 360px screen.`,
                        card: { title: 'Slow Card ' + id, thumb: `https://pbs.twimg.com/media/card_${id}.jpg` }
                    }));
                } else if (i % 3 === 1) {
                    forYouTweets1.push(makeGraphqlTweet(id, {
                        user: 'alice',
                        name: 'Alice',
                        text: `Tweet ${id} with slow portrait photo.`,
                        photo: { withDims: false, w: 360, h: 480 }
                    }));
                } else {
                    forYouTweets1.push(makeGraphqlTweet(id, {
                        user: 'carol',
                        name: 'Carol',
                        text: `Tweet ${id} with dimension-known photo.\nSecond line of commentary.\nThird line for height.`,
                        photo: { withDims: true, w: 360, h: 420 }
                    }));
                }
            }
            const forYouTweets2 = [
                makeGraphqlTweet('2901', { user: 'newuser', name: 'Fresh User', text: 'Reloaded For You post #1', photo: { withDims: true, w: 360, h: 300 } }),
                makeGraphqlTweet('2902', { user: 'newuser', name: 'Fresh User', text: 'Reloaded For You post #2' })
            ];
            const offlineBookmarks = Array.from({ length: 10 }, (_, idx) => {
                const id = String(5001 + idx);
                return {
                    id,
                    user_id: 'u_off',
                    user_name: 'Offline Author',
                    user_screen_name: 'offline_user',
                    user_avatar: 'https://pbs.twimg.com/profile_images/off_normal.jpg',
                    time_ms: Date.now() - idx * 60000,
                    text: `Offline tweet ${id} with photo and long description for scroll depth testing.`,
                    likes: 5, retweets: 1, replies: 1,
                    media: idx < 4 ? [{
                        type: 'photo',
                        url: `https://pbs.twimg.com/media/slow_${id}.jpg`,
                        width: 360,
                        height: 360
                    }] : []
                };
            });

            return `(() => {
                const timelineEntry = ${timelineEntry.toString()};
                const feedPage1 = ${JSON.stringify({ data: { home: { home_timeline_urt: { instructions: [{ type: 'TimelineAddEntries', entries: forYouTweets1.map((t, i) => timelineEntry(t, 100 - i)) }] } } } })};
                const feedPage2 = ${JSON.stringify({ data: { home: { home_timeline_urt: { instructions: [{ type: 'TimelineAddEntries', entries: forYouTweets2.map((t, i) => timelineEntry(t, 50 - i)) }] } } } })};

                window.__e2e.cacheStore.bookmarks = ${JSON.stringify(JSON.stringify(offlineBookmarks))};
                window.__e2e.cacheStore.feed_cache = '[]';
                if (window.XClientTransaction) {
                    window.XClientTransaction.ClientTransaction = class { generateTransactionId() { return 'e2e-tx'; } };
                }
                searchTransactionClient = { generateTransactionId: () => 'e2e-tx' };
                searchTransactionFetchedAt = Date.now();
                viewerIdentity = { id: 'u_me', screen_name: 'me_user', name: 'Me User' };

                const replyTweets = (focalId) => {
                    const focal = ${JSON.stringify(makeGraphqlTweet('FOCAL', { user: 'bob', name: 'Bob', text: 'Focal tweet text', photo: { withDims: true, w: 360, h: 300 } }))};
                    focal.rest_id = String(focalId);
                    focal.legacy.id_str = String(focalId);
                    const entries = [timelineEntry(focal, 1000)];
                    for (let r = 1; r <= 12; r++) {
                        const rid = focalId + '_r' + r;
                        const rep = ${JSON.stringify(makeGraphqlTweet('REP', { user: 'bob', name: 'Bob', text: 'Reply copy with sufficient vertical height for scroll verification across Mobile 360px viewport.\\nSecond line of reply.' }))};
                        rep.rest_id = rid;
                        rep.legacy.id_str = rid;
                        rep.legacy.full_text = 'Reply ' + rid + ' from @bob — detailed thread response so clicking reply or profile tests scroll preservation cleanly.';
                        rep.legacy.in_reply_to_status_id_str = String(focalId);
                        rep.legacy.conversation_id_str = String(focalId);
                        entries.push({
                            entryId: 'conversationthread-' + rid,
                            sortIndex: String(900 - r),
                            content: {
                                entryType: 'TimelineTimelineModule',
                                items: [{ entryId: 'tweet-' + rid, item: { itemContent: { itemType: 'TimelineTweet', tweet_results: { result: rep } } } }]
                            }
                        });
                    }
                    return { data: { threaded_conversation_with_injections_v2: { instructions: [{ type: 'TimelineAddEntries', entries }] } } };
                };

                const profileTweets1 = [];
                const profileTweets2 = [];
                for (let p = 1; p <= 5; p++) {
                    const tw = ${JSON.stringify(makeGraphqlTweet('P', { user: 'alice', name: 'Alice', text: 'Profile tweet page 1' }))};
                    tw.rest_id = 'prof_1_' + p;
                    tw.legacy.id_str = tw.rest_id;
                    tw.legacy.full_text = 'Profile tweet ' + p + ' line 1\\nLine 2\\nLine 3 for height.';
                    profileTweets1.push(tw);
                }
                for (let p = 6; p <= 8; p++) {
                    const tw = ${JSON.stringify(makeGraphqlTweet('P2', { user: 'alice', name: 'Alice', text: 'Profile tweet page 2' }))};
                    tw.rest_id = 'prof_2_' + p;
                    tw.legacy.id_str = tw.rest_id;
                    tw.legacy.full_text = 'Profile tweet page 2 item ' + p;
                    profileTweets2.push(tw);
                }

                const searchPage1 = [1, 2, 3].map(i => {
                    const tw = ${JSON.stringify(makeGraphqlTweet('S1', { user: 'alice', name: 'Alice', text: 'Search page 1 result' }))};
                    tw.rest_id = 'srch_1_' + i;
                    tw.legacy.id_str = tw.rest_id;
                    tw.legacy.full_text = 'Search p1 #' + i;
                    return tw;
                });
                const searchPage2Video = ${JSON.stringify(makeGraphqlTweet('srch_2_vid', { user: 'carol', name: 'Carol', text: 'Search p2 video result', video: { withDims: true } }))};
                const vidBudgetTweets = Array.from({ length: 8 }, (_, idx) => {
                    const tw = ${JSON.stringify(makeGraphqlTweet('VB', { user: 'carol', name: 'Carol', text: 'Video budget item', video: { withDims: true, w: 360, h: 420 } }))};
                    tw.rest_id = 'vb_' + idx;
                    tw.legacy.id_str = tw.rest_id;
                    tw.legacy.full_text = 'Video budget test #' + idx + '\\nTall card spacer line 1\\nTall card spacer line 2';
                    return tw;
                });

                window.__e2eDispatch = function(url, body, cbId) {
                    const respond = (obj) => {
                        setTimeout(() => {
                            if (window.__onAsyncFinish) window.__onAsyncFinish(cbId, JSON.stringify(obj));
                        }, 20);
                    };
                    if (url.includes('HomeTimeline')) {
                        window.__e2e.forYouRequests++;
                        return respond(window.__e2e.forYouRequests === 1 ? feedPage1 : feedPage2);
                    }
                    if (url.includes('HomeLatestTimeline')) {
                        window.__e2e.followingRequests++;
                        return respond(feedPage1);
                    }
                    if (url.includes('TweetDetail')) {
                        const m = decodeURIComponent(url).match(/"focalTweetId":"([^"]+)"/);
                        return respond(replyTweets(m ? m[1] : '1009'));
                    }
                    if (url.includes('UserByScreenName')) {
                        const m = decodeURIComponent(url).match(/"screen_name":"([^"]+)"/);
                        const sn = m ? m[1] : 'alice';
                        return respond({ data: { user: { result: { __typename: 'User', rest_id: 'u_' + sn, is_blue_verified: true, legacy: { name: sn.toUpperCase(), screen_name: sn, description: 'Bio for ' + sn, followers_count: 100, friends_count: 20, statuses_count: 50, profile_image_url_https: 'https://pbs.twimg.com/profile_images/' + sn + '_normal.jpg' } } } } });
                    }
                    if (url.includes('UserTweets')) {
                        const isPage2 = decodeURIComponent(url).includes('"cursor":"prof_cur_2"');
                        window.__e2e.profileRequests.push(isPage2 ? 2 : 1);
                        const list = isPage2 ? profileTweets2 : profileTweets1;
                        const entries = list.map((t, i) => timelineEntry(t, 500 - i));
                        if (!isPage2) entries.push({ entryId: 'cursor-bottom', sortIndex: '1', content: { entryType: 'TimelineTimelineCursor', cursorType: 'Bottom', value: 'prof_cur_2' } });
                        return respond({ data: { user: { result: { timeline_v2: { timeline: { instructions: [{ type: 'TimelineAddEntries', entries }] } } } } } });
                    }
                    if (url.includes('SearchTimeline')) {
                        const rawReq = decodeURIComponent(url) + ' ' + (body || '');
                        if (rawReq.includes('vidbudget')) {
                            return respond({ data: { search_by_raw_query: { search_timeline: { timeline: { instructions: [{ type: 'TimelineAddEntries', entries: vidBudgetTweets.map((t, i) => timelineEntry(t, 400 - i)) }] } } } } });
                        }
                        const isPage2 = decodeURIComponent(url).includes('"cursor":"srch_cur_2"') || (body && body.includes('srch_cur_2'));
                        window.__e2e.searchRequests.push(isPage2 ? 2 : 1);
                        const entries = isPage2
                            ? [timelineEntry(searchPage2Video, 200)]
                            : searchPage1.map((t, i) => timelineEntry(t, 300 - i)).concat([{ entryId: 'cursor-bottom', sortIndex: '1', content: { entryType: 'TimelineTimelineCursor', cursorType: 'Bottom', value: 'srch_cur_2' } }]);
                        return respond({ data: { search_by_raw_query: { search_timeline: { timeline: { instructions: [{ type: 'TimelineAddEntries', entries }] } } } } });
                    }
                    if (url.includes('notifications/all.json')) {
                        return respond({
                            globalObjects: {
                                notifications: { n1: { id: 'n1', timestampMs: String(Date.now()), message: { text: 'Alice liked your post', entities: [{ ref: { user: { id: 'u_alice' } } }] }, template: { aggregateUserActionsV1: { targetObjects: [{ tweet: { id: '1009' } }], fromUsers: [{ user: { id: 'u_alice' } }] } } } },
                                tweets: { '1009': { id_str: '1009', full_text: 'Tweet 1009 referenced in notification', user_id_str: 'u_alice', created_at: 'Fri Nov 14 10:00:00 +0000 2026' } },
                                users: { u_alice: { id_str: 'u_alice', name: 'Alice', screen_name: 'alice', profile_image_url_https: 'https://pbs.twimg.com/profile_images/alice_normal.jpg' } }
                            },
                            timeline: { instructions: [{ addEntries: { entries: [{ entryId: 'notif-n1', sortIndex: '10', content: { item: { content: { notification: { id: 'n1' } } } } }] } }] }
                        });
                    }
                    return respond({ data: {} });
                };

                switchFeed('foryou');
                return 'BOOTED';
            })()`;
        };

        assert.equal(await evalExpr(makeFixtureScript()), 'BOOTED');
        await sleep(300);

        const results = [];
        const check = async (name, fn) => {
            try {
                await fn();
                results.push({ name, ok: true });
                console.log('PASS:', name);
            } catch (err) {
                results.push({ name, ok: false, error: err.message });
                console.log('FAIL:', name, '->', err.message);
            }
        };

        // 1. Feed -> Thread -> Back with delayed (>400ms) layout-shifting media
        await check('1. ForYou Feed -> Thread -> Back preserves scroll past 400ms layout shifts & does not reload ForYou', async () => {
            const forYouBefore = await evalExpr('window.__e2e.forYouRequests');
            await evalExpr(`(() => {
                const el = document.querySelector('[data-id="1009"]');
                el.scrollIntoView({ block: 'start' });
                window.scrollBy(0, -110);
            })()`);
            await sleep(80);
            const topBefore = await evalExpr(`document.querySelector('[data-id="1009"]').getBoundingClientRect().top`);
            slowDelayMs = 650;
            forceDelayedMedia = true;
            await evalExpr(`viewThread('1009')`);
            await sleep(150);
            // Simulate above-fold slow cards/images finishing decode/layout at 550ms (> 400ms cutoff)
            await evalExpr(`(() => { history.back(); })()`);
            await sleep(550);
            await evalExpr(`(() => {
                const aboveCard = document.querySelector('[data-id="1003"] .tweet-text');
                if (aboveCard) aboveCard.style.paddingBottom = '600px';
            })()`);
            // Wait for ResizeObserver / settlement window to finish correcting scroll
            await sleep(550);
            const state = await evalExpr(`(() => ({
                view: currentView,
                tab: currentTab,
                forYouReqs: window.__e2e.forYouRequests,
                topAfter: document.querySelector('[data-id="1009"]')?.getBoundingClientRect()?.top ?? null
            }))()`);
            forceDelayedMedia = false;
            slowDelayMs = 60;
            assert.equal(state.view, 'feed');
            assert.equal(state.tab, 'foryou');
            assert.equal(state.forYouReqs, forYouBefore, 'For You must not reload when returning');
            assert.notEqual(state.topAfter, null, 'Anchor tweet 1009 must still exist');
            assert.ok(Math.abs(state.topAfter - topBefore) <= 24, `Scroll drifted: before=${topBefore}, after=${state.topAfter}`);
        });

        // 2. Link card aspect-ratio reserves height BEFORE delayed image arrives (zero 0 -> 183px jump!)
        await check('2. Link card image reserves layout height before load (prevents late card expansion shift)', async () => {
            slowDelayMs = 700;
            const heights = await evalExpr(`(() => {
                const cards = Array.from(document.querySelectorAll('.link-card-img'));
                return cards.map(img => img.getBoundingClientRect().height);
            })()`);
            assert.ok(heights.length > 0 && heights.every(h => h >= 100), `Expected pre-reserved link-card heights >= 100px, got ${JSON.stringify(heights)}`);
            slowDelayMs = 60;
        });

        // 3. Feed -> @alice Profile -> Back preserves Feed & does NOT exit / reload
        await check('3. Feed -> Profile -> Back preserves Feed scroll & pushes history entry', async () => {
            await evalExpr(`(() => {
                const el = document.querySelector('[data-id="1009"]');
                el.scrollIntoView({ block: 'start' });
                window.scrollBy(0, -110);
            })()`);
            await sleep(50);
            const topBefore = await evalExpr(`document.querySelector('[data-id="1009"]').getBoundingClientRect().top`);
            const reqsBefore = await evalExpr(`window.__e2e.forYouRequests`);
            const activeBefore = await evalExpr(`activeHistoryPushes`);
            await evalExpr(`openProfile('alice')`);
            await sleep(150);
            const inProfile = await evalExpr(`({ view: currentView, state: history.state, activePushes: activeHistoryPushes, hash: location.hash })`);
            assert.equal(inProfile.view, 'profile');
            assert.equal(inProfile.state?.view, 'profile', 'Opening profile must push profile history.state for hardware Back');
            assert.ok(Number(inProfile.state?.stackDepth) > 0, 'Profile history entry must carry positive stackDepth');
            assert.equal(inProfile.activePushes, activeBefore + 1, 'activeHistoryPushes must increment on openProfile');
            // Click UI Back button in profile header
            await evalExpr(`document.querySelector('.sub-header .back-btn').click()`);
            await sleep(180);
            const backState = await evalExpr(`({
                view: currentView,
                reqsAfter: window.__e2e.forYouRequests,
                topAfter: document.querySelector('[data-id="1009"]')?.getBoundingClientRect()?.top ?? null
            })`);
            assert.equal(backState.view, 'feed');
            assert.equal(backState.reqsAfter, reqsBefore, 'Returning from Profile must not reload For You');
            assert.ok(Math.abs(backState.topAfter - topBefore) <= 24, `Drift after Profile->Back: before=${topBefore}, after=${backState.topAfter}`);
        });

        // 4. Multi-hop stack: Feed -> Thread A (scrolled to reply) -> @bob Profile -> Thread B -> Back -> Back -> Back
        await check('4. Multi-hop Feed -> Thread A -> @bob Profile -> Thread B -> Back x3 returns to every intermediate view cleanly', async () => {
            await evalExpr(`(() => {
                document.querySelector('[data-id="1009"]').scrollIntoView({ block: 'start' });
                window.scrollBy(0, -110);
            })()`);
            const feedTopBefore = await evalExpr(`document.querySelector('[data-id="1009"]').getBoundingClientRect().top`);
            await evalExpr(`viewThread('1009')`);
            await sleep(180);
            await evalExpr(`(() => {
                const r6 = document.querySelector('[data-id="1009_r6"]');
                r6.scrollIntoView({ block: 'start' });
                window.scrollBy(0, -70);
            })()`);
            const r6TopBefore = await evalExpr(`document.querySelector('[data-id="1009_r6"]').getBoundingClientRect().top`);

            await evalExpr(`openProfile('bob')`);
            await sleep(180);
            assert.equal(await evalExpr(`currentView + ':' + profileState?.screenName`), 'profile:bob');

            await evalExpr(`viewThread('prof_1_2')`);
            await sleep(150);
            assert.equal(await evalExpr(`currentView + ':' + activeThreadTweetId`), 'thread:prof_1_2');

            // Back #1 -> Profile @bob
            await evalExpr(`history.back()`);
            await sleep(180);
            assert.equal(await evalExpr(`currentView + ':' + profileState?.screenName`), 'profile:bob');

            // Back #2 -> Thread A with scroll restored to r6
            await evalExpr(`history.back()`);
            await sleep(180);
            const threadA = await evalExpr(`({
                view: currentView,
                id: activeThreadTweetId,
                r6Top: document.querySelector('[data-id="1009_r6"]')?.getBoundingClientRect()?.top ?? null
            })`);
            assert.equal(threadA.view, 'thread');
            assert.equal(threadA.id, '1009');
            assert.ok(threadA.r6Top !== null && Math.abs(threadA.r6Top - r6TopBefore) <= 24, `Thread A scroll drifted: before=${r6TopBefore}, after=${threadA.r6Top}`);

            // Back #3 -> Feed with 1009 restored
            await evalExpr(`history.back()`);
            await sleep(180);
            const feedAfter = await evalExpr(`({
                view: currentView,
                inFeedBody: document.body.classList.contains('in-feed'),
                tabDisplay: document.getElementById('tabNav').style.display,
                topAfter: document.querySelector('[data-id="1009"]')?.getBoundingClientRect()?.top ?? null
            })`);
            assert.equal(feedAfter.view, 'feed');
            assert.equal(feedAfter.inFeedBody, true);
            assert.equal(feedAfter.tabDisplay, 'flex');
            assert.ok(Math.abs(feedAfter.topAfter - feedTopBefore) <= 24, `Feed drifted after multi-hop: before=${feedTopBefore}, after=${feedAfter.topAfter}`);
        });

        // 5. Lightbox Back closes lightbox without popping underlying view
        await check('5. Lightbox open -> history.back() closes lightbox while keeping underlying view intact', async () => {
            await evalExpr(`openLightbox('1004', 0)`);
            await sleep(80);
            assert.equal(await evalExpr(`document.getElementById('mediaLightbox').classList.contains('open')`), true);
            await evalExpr(`history.back()`);
            await sleep(120);
            const state = await evalExpr(`({
                lbOpen: document.getElementById('mediaLightbox').classList.contains('open'),
                view: currentView
            })`);
            assert.equal(state.lbOpen, false);
            assert.equal(state.view, 'feed');
        });

        // 6. Notifications -> Back restores Feed
        await check('6. Feed -> Alerts (Notifications) -> Back preserves Feed & scroll', async () => {
            await evalExpr(`(() => {
                document.querySelector('[data-id="1009"]').scrollIntoView({ block: 'start' });
                window.scrollBy(0, -110);
            })()`);
            const topBefore = await evalExpr(`document.querySelector('[data-id="1009"]').getBoundingClientRect().top`);
            const reqsBefore = await evalExpr(`window.__e2e.forYouRequests`);
            await evalExpr(`document.getElementById('hdrNotifBtn').click()`);
            await sleep(150);
            assert.equal(await evalExpr(`currentView`), 'notifications');
            await evalExpr(`document.querySelector('.sub-header .back-btn').click()`);
            await sleep(150);
            const after = await evalExpr(`({
                view: currentView,
                reqsAfter: window.__e2e.forYouRequests,
                topAfter: document.querySelector('[data-id="1009"]')?.getBoundingClientRect()?.top ?? null
            })`);
            assert.equal(after.view, 'feed');
            assert.equal(after.reqsAfter, reqsBefore);
            assert.ok(Math.abs(after.topAfter - topBefore) <= 24);
        });

        // 7. Offline ('cache') tab + 'tap' mode expanded images survive Thread -> Back
        await check('7. Offline (cache) tab with tap-expanded images preserves expanded DOM & scroll across Thread -> Back', async () => {
            await evalExpr(`(() => {
                saveImageModePref('tap');
                appConfig.image_mode = 'tap';
                switchFeed('cache');
            })()`);
            await sleep(120);
            // Expand first two tap images
            await evalExpr(`(() => {
                const btns = Array.from(document.querySelectorAll('[data-id="5001"] button, [data-id="5002"] button')).filter(b => b.textContent.includes('Load small preview'));
                btns.forEach(b => b.click());
            })()`);
            await sleep(150);
            await evalExpr(`(() => {
                document.querySelector('[data-id="5007"]').scrollIntoView({ block: 'start' });
                window.scrollBy(0, -110);
            })()`);
            const topBefore = await evalExpr(`document.querySelector('[data-id="5007"]').getBoundingClientRect().top`);
            await evalExpr(`viewThread('5007')`);
            await sleep(120);
            await evalExpr(`history.back()`);
            await sleep(200);
            const after = await evalExpr(`({
                view: currentView,
                tab: currentTab,
                expandedImgs: document.querySelectorAll('[data-id="5001"] img.tweet-img, [data-id="5002"] img.tweet-img').length,
                topAfter: document.querySelector('[data-id="5007"]')?.getBoundingClientRect()?.top ?? null
            })`);
            assert.equal(after.view, 'feed');
            assert.equal(after.tab, 'cache');
            assert.equal(after.expandedImgs, 2, 'Tap-expanded images in Offline tab must stay expanded');
            assert.ok(Math.abs(after.topAfter - topBefore) <= 24, `Offline scroll drifted: before=${topBefore}, after=${after.topAfter}`);
            await evalExpr(`(() => { saveImageModePref('auto'); appConfig.image_mode = 'auto'; switchFeed('foryou'); })()`);
            await sleep(120);
        });

        // 8. Profile Load More preserves existing DOM nodes (append-only)
        await check('8. Profile Load More appends page 2 tweets without replacing existing DOM', async () => {
            await evalExpr(`openProfile('alice')`);
            await sleep(150);
            await evalExpr(`window.__p1El = document.querySelector('[data-id="prof_1_1"]');`);
            await evalExpr(`loadMoreProfileTweets()`);
            await sleep(150);
            const res = await evalExpr(`({
                connected: window.__p1El && window.__p1El.isConnected,
                hasPage2: Boolean(document.querySelector('[data-id="prof_2_6"]'))
            })`);
            assert.equal(res.connected, true, 'Existing profile tweet DOM node was wiped during pagination');
            assert.equal(res.hasPage2, true, 'Page 2 profile tweet was not rendered');
        });

        // 9. Search Page 2 attaches video observer to newly appended videos
        await check('9. Search Page 2 observes new video elements with videoAutoPauseObserver', async () => {
            await evalExpr(`nav('search')`);
            await sleep(80);
            await evalExpr(`(() => {
                document.getElementById('searchInput').value = 'videos';
                executeSearch({ preventDefault() {} });
            })()`);
            await sleep(150);
            await evalExpr(`loadMoreSearch()`);
            await sleep(150);
            const res = await evalExpr(`(() => {
                const v = document.querySelector('[data-id="srch_2_vid"] video.tweet-video');
                return { hasVideo: Boolean(v), preload: v ? v.preload : '', observed: v ? v.dataset.xliteObserved === '1' : false };
            })()`);
            assert.equal(res.hasVideo, true);
            assert.equal(res.observed, true, 'Search Page 2 video must be attached to video observer');
        });

        // 10. Distant scrolled-past paused videos (>4 away) park decoder src & restore on scroll back
        await check('10. Video decoder budget parks distant untouched videos (>4 away) and restores on scroll back', async () => {
            await evalExpr(`nav('search')`);
            await sleep(80);
            await evalExpr(`(() => {
                document.getElementById('searchInput').value = 'vidbudget';
                executeSearch({ preventDefault() {} });
            })()`);
            await sleep(300);
            await evalExpr(`document.querySelector('[data-id="vb_0"] video')?.scrollIntoView({ block: 'center' })`);
            await sleep(300);
            const firstLoaded = await evalExpr(`(() => {
                const v0 = document.querySelector('[data-id="vb_0"] video');
                return { ready: v0 ? v0.readyState : -1, src: v0 ? (v0.getAttribute('src') || '') : '' };
            })()`);
            assert.ok(firstLoaded.ready >= 1 && firstLoaded.src.length > 0, `First video should load metadata/frame first (ready=${firstLoaded.ready})`);
            // Scroll down to vb_7 (distance = 7 > 4) and pause v0 if autoplaying
            await evalExpr(`(() => {
                const v0 = document.querySelector('[data-id="vb_0"] video');
                if (v0) { v0.pause(); v0.currentTime = 0; }
                document.querySelector('[data-id="vb_7"] video')?.scrollIntoView({ block: 'center' });
            })()`);
            await sleep(350);
            const parked = await evalExpr(`(() => {
                const v0 = document.querySelector('[data-id="vb_0"] video');
                return {
                    preload: v0 ? v0.preload : '',
                    srcAttr: v0 ? v0.getAttribute('src') : 'missing',
                    parkedSrc: v0?.dataset?.xliteParkedSrc || ''
                };
            })()`);
            assert.equal(parked.preload, 'none', 'Distant untouched video must downgrade preload to none');
            assert.equal(parked.srcAttr, null, 'Distant untouched video must release src attribute');
            assert.ok(parked.parkedSrc.length > 0, 'Distant untouched video must stash xliteParkedSrc');
            // Scroll back to vb_0 and confirm parked src restores
            await evalExpr(`document.querySelector('[data-id="vb_0"] video')?.scrollIntoView({ block: 'center' })`);
            await sleep(300);
            const restored = await evalExpr(`(() => {
                const v0 = document.querySelector('[data-id="vb_0"] video');
                return {
                    srcAttr: v0 ? (v0.getAttribute('src') || '') : '',
                    parkedSrc: v0?.dataset?.xliteParkedSrc || ''
                };
            })()`);
            assert.ok(restored.srcAttr.length > 0, 'Scrolling back must restore video src');
            assert.equal(restored.parkedSrc, '', 'Scrolling back must clear xliteParkedSrc');
        });

        // 11. Settings pref change + Hardware Back reloads Feed instead of restoring stale DOM snapshot
        await check('11. Live Settings toggle invalidates Feed snapshot so Back reloads Feed cleanly', async () => {
            await evalExpr(`nav('feed')`);
            await sleep(180);
            const reqsBefore = await evalExpr(`window.__e2e.forYouRequests`);
            await evalExpr(`nav('settings')`);
            await sleep(100);
            await evalExpr(`(() => {
                const tapRadio = document.querySelector('input[name="imgMode"][value="tap"]');
                tapRadio.checked = true;
                handleSettingPrefChange('imgMode', 'tap');
            })()`);
            await sleep(80);
            await evalExpr(`history.back()`);
            await sleep(200);
            const after = await evalExpr(`({
                view: currentView,
                reqsAfter: window.__e2e.forYouRequests,
                mode: appConfig.image_mode,
                hasTapBtn: Boolean(Array.from(document.querySelectorAll('[data-id="2901"] button')).find(b => b.textContent.includes('Load small preview'))),
                hasAutoImg: Boolean(document.querySelector('[data-id="2901"] img.tweet-img'))
            })`);
            assert.equal(after.view, 'feed');
            assert.ok(after.reqsAfter > reqsBefore, 'Returning after Settings change must reload Feed');
            assert.equal(after.mode, 'tap');
            assert.equal(after.hasTapBtn, true, 'Reloaded feed must render tap preview button for updated preference');
            assert.equal(after.hasAutoImg, false, 'Stale auto-loaded photo must not be restored after Settings change');
        });

        const failed = results.filter(r => !r.ok);
        console.log(`\nChrome E2E Summary: ${results.length - failed.length}/${results.length} passed, ${failed.length} failed.`);
        if (failed.length > 0) process.exitCode = 1;
    } finally {
        if (cdp) cdp.close();
        try { chrome.kill('SIGKILL'); } catch (_) {}
        fs.rmSync(userDataDir, { recursive: true, force: true });
        server.close();
    }
}

runTests().catch(err => {
    console.error(err);
    process.exit(1);
});
