import proc from 'child_process';
import fetch from 'isomorphic-fetch';
import webdriver from 'w3c-webdriver';

// (async () => {
//     try {

//         await session.go('http://localhost:8080');
//         const input = await session.findElement('css selector', '[name="first-name"]');
//         await a.sendKeys('Hello World');
//     } catch (err) {
//         console.log(err.stack);
//     } finally {
//         session.delete();
//     }
// })();

const BASE_URL = `http://127.0.0.1:4444`;

export default class WebDriver {
    constructor() {
        console.log('Starting webdriver...')
        this.proc = proc.spawn('chromedriver', ['--port=4444']);
        this.proc.stdout.on('data', async (data) => console.log(data.toString()));
        this.proc.stderr.on('data', (data) => console.error(data.toString()));
        console.log('Started webdriver...')
    }

    async getStatus() {
        const res = await fetch(`${BASE_URL}/status`);
        const obj = await res.json();
        return obj;
    }

    async createSession() {
        this.session = await webdriver.newSession('http://localhost:4444', {
            desiredCapabilities: {
                browserName: 'Chrome'
            }
        });
    }

    async visit(url) {
        await this.session.go(url);
    }

    async find(qs) {
        return await this.session.findElements('css selector', qs);
    }

    async close() {
        console.log('Terminating webdriver...')
        await this.session.delete();
        this.proc.stdin.pause();
        this.proc.kill('SIGKILL');
    }
}