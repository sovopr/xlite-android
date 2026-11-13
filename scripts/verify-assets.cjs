'use strict';

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const ROOT = path.resolve(__dirname, '..');

function fail(msg) {
    console.error(`FAIL: ${msg}`);
    process.exit(1);
}

function readText(relPath) {
    const abs = path.join(ROOT, relPath);
    if (!fs.existsSync(abs)) {
        fail(`Missing expected file: ${relPath}`);
    }
    return fs.readFileSync(abs, 'utf8');
}

function walkFiles(dir, predicate) {
    const out = [];
    if (!fs.existsSync(dir)) return out;
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        if (entry.name === '.git' || entry.name === '.gradle' || entry.name === 'build') continue;
        const abs = path.join(dir, entry.name);
        if (entry.isDirectory()) {
            out.push(...walkFiles(abs, predicate));
        } else if (!predicate || predicate(abs)) {
            out.push(abs);
        }
    }
    return out;
}

function checkEmbeddedScripts(htmlText) {
    const scriptRe = /<script\b(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/gi;
    let count = 0;
    let match;
    while ((match = scriptRe.exec(htmlText)) !== null) {
        const body = match[1];
        if (!body.trim()) continue;
        count += 1;
        try {
            new vm.Script(body, { filename: `app/src/main/assets/index.html#script-${count}` });
        } catch (err) {
            fail(`Invalid inline JS in index.html script #${count}: ${err.message}`);
        }
    }
    if (count === 0) {
        fail('Expected at least one inline <script> block in app/src/main/assets/index.html');
    }
    return count;
}

function checkVersionParity(gradleText, htmlText) {
    const gradleVerMatch = gradleText.match(/(?:val\s+appVersion[^\n]*\?:\s*"|versionName\s*=\s*")(\d+\.\d+\.\d+)"/);
    if (!gradleVerMatch) {
        fail('Could not parse semantic versionName ("X.Y.Z") from app/build.gradle.kts');
    }
    const gradleVer = gradleVerMatch[1];

    const gradleCodeMatch = gradleText.match(/versionCode\s*=\s*(?:[^\n]*\belse\s+)?(\d+)/);
    if (!gradleCodeMatch || Number(gradleCodeMatch[1]) <= 0) {
        fail('Could not parse positive integer versionCode from app/build.gradle.kts');
    }
    const gradleCode = Number(gradleCodeMatch[1]);

    const appVerMatch = htmlText.match(/const APP_VERSION = '(\d+\.\d+\.\d+)';/);
    const apkVerMatch = htmlText.match(/const LATEST_APK_VERSION = '(\d+\.\d+\.\d+)';/);
    const apkUrlMatch = htmlText.match(/const LATEST_APK_URL = '([^']+)';/);
    const apkShaMatch = htmlText.match(/const LATEST_APK_SHA256 = '([0-9a-fA-F]{64}|)';/);

    if (!appVerMatch || !apkVerMatch || !apkUrlMatch || !apkShaMatch) {
        fail('Missing required version/OTA constants in app/src/main/assets/index.html');
    }

    if (appVerMatch[1] !== gradleVer) {
        fail(`APP_VERSION (${appVerMatch[1]}) !== Gradle versionName (${gradleVer})`);
    }
    if (apkVerMatch[1] !== gradleVer) {
        fail(`LATEST_APK_VERSION (${apkVerMatch[1]}) !== Gradle versionName (${gradleVer})`);
    }
    if (!apkUrlMatch[1].startsWith('https://github.com/sovopr/xlite-android/releases/')) {
        fail(`Unexpected LATEST_APK_URL: ${apkUrlMatch[1]}`);
    }

    const rawTag = (process.env.RELEASE_TAG || '').trim();
    if (rawTag && rawTag.startsWith('v')) {
        const tagVer = rawTag.slice(1);
        if (tagVer !== gradleVer) {
            fail(`RELEASE_TAG (${rawTag}) !== Gradle versionName (${gradleVer})`);
        }
    }

    return { version: gradleVer, versionCode: gradleCode };
}

function stripXmlCommentsAndCdata(xml) {
    return xml
        .replace(/<\?[\s\S]*?\?>/g, '')
        .replace(/<!--[\s\S]*?-->/g, '')
        .replace(/<!\[CDATA\[[\s\S]*?\]\]>/g, '');
}

function checkXmlAndSvgFiles() {
    const xmlTargets = [
        path.join(ROOT, 'app/src/main/AndroidManifest.xml'),
        ...walkFiles(path.join(ROOT, 'app/src/main/res'), p => p.endsWith('.xml')),
        path.join(ROOT, 'logo.svg'),
        ...walkFiles(path.join(ROOT, 'docs'), p => p.endsWith('.svg')),
    ];

    for (const absPath of xmlTargets) {
        const rel = path.relative(ROOT, absPath);
        if (!fs.existsSync(absPath)) {
            fail(`Missing expected XML/SVG file: ${rel}`);
        }
        const raw = fs.readFileSync(absPath, 'utf8');
        const cleaned = stripXmlCommentsAndCdata(raw);

        if (/&(?!(?:amp|lt|gt|quot|apos|#\d+|#x[0-9a-fA-F]+);)/.test(cleaned)) {
            fail(`Unescaped '&' in ${rel}`);
        }

        const stack = [];
        const tagRe = /<(\/?)([A-Za-z_][\w:.-]*)([^>]*?)(\/?)>/g;
        let m;
        while ((m = tagRe.exec(cleaned)) !== null) {
            const isClose = m[1] === '/';
            const name = m[2];
            const isSelf = m[4] === '/';
            if (isClose && isSelf) {
                fail(`Malformed tag </${name}/> in ${rel}`);
            } else if (!isClose && !isSelf) {
                stack.push(name);
            } else if (isClose) {
                const top = stack.pop();
                if (top !== name) {
                    fail(`Mismatched closing tag </${name}> (expected </${top || 'EOF'}>) in ${rel}`);
                }
            }
        }
        if (stack.length !== 0) {
            fail(`Unclosed tag <${stack[stack.length - 1]}> in ${rel}`);
        }
        if (absPath.endsWith('.svg') && !/<svg\b[^>]*\bviewBox=/.test(cleaned)) {
            fail(`SVG ${rel} is missing a viewBox attribute`);
        }
    }

    return xmlTargets.length;
}

function checkImageMagicHeaders() {
    const docsDir = path.join(ROOT, 'docs');
    const images = walkFiles(docsDir, p => p.endsWith('.png') || p.endsWith('.webp'));
    for (const imgPath of images) {
        const rel = path.relative(ROOT, imgPath);
        const buf = fs.readFileSync(imgPath);
        if (imgPath.endsWith('.png')) {
            const pngMagic = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
            if (buf.length < 24 || !buf.subarray(0, 8).equals(pngMagic)) {
                fail(`Invalid PNG header in ${rel}`);
            }
        } else if (imgPath.endsWith('.webp')) {
            if (
                buf.length < 24 ||
                buf.subarray(0, 4).toString('ascii') !== 'RIFF' ||
                buf.subarray(8, 12).toString('ascii') !== 'WEBP'
            ) {
                fail(`Invalid WebP header in ${rel}`);
            }
        }
    }
    return images.length;
}

function checkRequiredRepoFilesAndLinks(version) {
    const requiredFiles = [
        'README.md',
        'LICENSE',
        'SECURITY.md',
        'CONTRIBUTING.md',
        'CODE_OF_CONDUCT.md',
        'docs/logo.svg',
        'docs/social-preview.svg',
        'docs/social-preview.png',
        'docs/dependency-security.md',
        `docs/releases/v${version}.md`,
        'docs/screenshots/feed.webp',
        'docs/screenshots/thread.webp',
        '.github/ISSUE_TEMPLATE/bug_report.md',
        '.github/ISSUE_TEMPLATE/feature_request.md',
        '.github/pull_request_template.md',
        '.github/dependabot.yml',
        '.github/workflows/ci.yml',
        '.github/workflows/codeql.yml',
        '.github/workflows/build-apk.yml',
        '.github/workflows/dependency-submission.yml',
    ];
    for (const rel of requiredFiles) {
        if (!fs.existsSync(path.join(ROOT, rel))) {
            fail(`Missing required repository artifact: ${rel}`);
        }
    }

    const readme = readText('README.md');
    const linkRe = /!?\[[^\]]*\]\(([^)]+)\)/g;
    let linkCount = 0;
    let m;
    while ((m = linkRe.exec(readme)) !== null) {
        const target = m[1].trim().split('#')[0];
        if (!target || target.startsWith('http://') || target.startsWith('https://') || target.startsWith('mailto:')) {
            continue;
        }
        linkCount += 1;
        const resolved = path.resolve(ROOT, target);
        if (!fs.existsSync(resolved)) {
            fail(`Broken relative link in README.md: ${m[1]}`);
        }
    }
    return { requiredCount: requiredFiles.length, localLinkCount: linkCount };
}

function checkNoSecrets() {
    const scanRoots = ['app/src', 'docs', 'scripts', '.github', 'README.md', 'SECURITY.md', 'CONTRIBUTING.md'];
    const filesToScan = [];
    for (const entry of scanRoots) {
        const abs = path.join(ROOT, entry);
        if (!fs.existsSync(abs)) continue;
        if (fs.statSync(abs).isDirectory()) {
            filesToScan.push(
                ...walkFiles(abs, p => /\.(kt|kts|html|cjs|js|mjs|yml|yaml|md|svg|xml|txt)$/i.test(p))
            );
        } else {
            filesToScan.push(abs);
        }
    }

    const secretPatterns = [
        { name: 'Private Key Header', re: /-----BEGIN (?:RSA |EC |DSA |OPENSSH |PGP )?PRIVATE KEY-----/ },
        { name: 'X auth_token Cookie', re: /\bauth_token=[0-9a-f]{32,}\b/i },
        { name: 'X ct0 Cookie', re: /\bct0=[0-9a-f]{32,}\b/i },
        { name: 'GitHub PAT', re: /\b(?:ghp|github_pat)_[A-Za-z0-9_]{20,}\b/ },
        { name: 'Google API Key', re: /\bAIza[0-9A-Za-z\-_]{30,}\b/ },
    ];

    for (const absPath of filesToScan) {
        const rel = path.relative(ROOT, absPath);
        const text = fs.readFileSync(absPath, 'utf8');
        for (const pat of secretPatterns) {
            if (pat.re.test(text)) {
                fail(`Potential secret (${pat.name}) detected in ${rel}`);
            }
        }
    }
    return filesToScan.length;
}

function main() {
    const gradleText = readText('app/build.gradle.kts');
    const htmlText = readText('app/src/main/assets/index.html');

    const scriptCount = checkEmbeddedScripts(htmlText);
    const { version, versionCode } = checkVersionParity(gradleText, htmlText);
    const scannedCount = checkNoSecrets();

    const strictDocs = process.argv.includes('--strict-docs') || !process.argv.includes('--core-only');
    const xmlCount = checkXmlAndSvgFiles();
    const imgCount = checkImageMagicHeaders();
    let repoStats = { requiredCount: 0, localLinkCount: 0 };
    if (strictDocs) {
        repoStats = checkRequiredRepoFilesAndLinks(version);
    }

    console.log(
        `Asset & repo verification passed (version=v${version} code=${versionCode}, ` +
        `scripts=${scriptCount}, xml/svg=${xmlCount}, images=${imgCount}, ` +
        `requiredFiles=${repoStats.requiredCount}, readmeLocalLinks=${repoStats.localLinkCount}, ` +
        `secretScanned=${scannedCount}).`
    );
}

main();
