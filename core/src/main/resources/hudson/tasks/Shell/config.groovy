/*
 * The MIT License
 *
 * Copyright (c) 2011, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.tasks.Shell;
f=namespace(lib.FormTagLib)

f.entry(title:_("Command"),description:_("description",rootURL)) {
    // TODO JENKINS-23151 'codemirror-mode': 'shell' is broken
    f.textarea(name: "command", value: instance?.command, class: "fixed-width")
}

f.advanced() {
    f.entry(title:_("Signal sent on abort"),
            description:_("signal sent to shell processes when clicking on job abort button"),
            field:"signalForAbort") {
        f.textbox()
    }
    f.entry(title:_("Max seconds after process abort"),
            description:_("check every second if process is living or not, kill with SIGTERM after timeout"),
            field:"timeoutForAbort") {
        f.textbox()
    }
}
