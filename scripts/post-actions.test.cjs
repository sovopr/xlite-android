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
    console.log('Async post and reply tests passed.');
}, 30);
