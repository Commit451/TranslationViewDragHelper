# TranslationViewDragHelper
A version of [ViewDragHelper](https://developer.android.com/reference/android/support/v4/widget/ViewDragHelper.html) which accounts for X and Y translations

[![Build Status](https://travis-ci.org/Commit451/TranslationViewDragHelper.svg?branch=master)](https://travis-ci.org/Commit451/TranslationViewDragHelper)
[![](https://jitpack.io/v/Commit451/TranslationViewDragHelper.svg)](https://jitpack.io/#Commit451/TranslationViewDragHelper)

# Usage
If you have ever used a `ViewDragHelper` before, you may have noticed that if you were to translate your views using View.setX() or View.setY(), your helper would no longer correctly allow you to drag the views. This library seeks to correct this. Usage is identical to `ViewDragHelper` usage
```java
TranslationViewDragHelper viewDragHelper = TranslationViewDragHelper.create(
                                            viewGroup,
                                            1.0f,
                                            viewDragHelperCallback);

//Somewhere else...
TranslationViewDragHelper.Callback mCallback = new TranslationViewDragHelper.Callback() {
        @Override
        public boolean tryCaptureView(View child, int pointerId) {
            //Any children can be captured
            return true;
        }

        @Override
        public void onViewPositionChanged(View changedView, int left, int top, int dx, int dy) {
            super.onViewPositionChanged(changedView, left, top, dx, dy);
            //Simply set the views translation to the new position
            changedView.setX(left);
            changedView.setY(top);
        }

        @Override
        public int clampViewPositionHorizontal(View child, int left, int dx) {
            //allow full movement along horizontal axis
            return left;
        }

        @Override
        public int clampViewPositionVertical(View child, int top, int dy) {
            //allow full movement along vertical axis
            return top;
        }
    };
```

# Basis
The current version of this helper is derived from `ViewDragHelper` source from 23.4.0 of the Support Library

License
--------

    Copyright 2016 Commit 451

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
