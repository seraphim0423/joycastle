using SLua;
using System;
using System.Collections;
using System.Collections.Generic;
using UnityEngine;
using UnityEngine.UI;


namespace UnityEngine.UI
{
    [AddComponentMenu("UI/UIMessageQueue")]
    [DisallowMultipleComponent]
    public partial class UIMessageQueue : UIListBase
    {
        public enum ArrangeMode
        {
            LeftToRight,
            RightToLeft,
            TopToBottom,
            BottomToTop,
        }

        private enum State
        {
            TWEEN,
            SCROLL,
            STAY,
        }

        private class MessageItem
        {
            public UIListItem item;
            RectTransform rectTransform;
            public UIPlayTweens tweens;
            internal Action<UIListItem> onTweenEnd;
            private bool playedTween;
            Vector2 scrollBase;

            internal void OnAppear(Vector2 appearPos)
            {
                scrollBase = rectTransform.anchoredPosition = appearPos;
                playedTween = false;
            }

            internal void SetActive(bool b)
            {
                item.SetActive(b);
            }
            internal MessageItem(UIListItem item, Vector2 cellSize)
            {
                this.item = item;
                rectTransform = item.GetComponent<RectTransform>();
                rectTransform.anchoredPosition3D = Vector3.zero;
                tweens = item.GetComponent<UIPlayTweens>();
                rectTransform.sizeDelta = cellSize;
            }

            internal bool PlayTween()
            {
                if (playedTween) return false;
                SetActive(true);
                tweens.Play(true);
                playedTween = true;
                return true;
            }

            internal bool IsPlayingTween()
            {
                return tweens.IsPlaying;
            }

            internal void OnBeginScroll()
            {
                scrollBase = rectTransform.anchoredPosition;
            }

            internal void Scroll(Vector2 delta)
            {
                rectTransform.anchoredPosition = scrollBase + delta;
            }
        }

        public ArrangeMode arrangeMode = ArrangeMode.TopToBottom;
        public Vector2 itemSize;
        public float scrollSpeed;
        //space between items
        public float space;
        // max show item count
        public int maxCount;
        //remove delay when stay state elapsed
        public float removeTime;
        private float stayTime;
        private State state;
        private Vector2 direction;
        private float cellDelta;
        private Vector2 scrollTarget;
        private float elapsedTime;
        private List<MessageItem> appearing = new List<MessageItem>();
        private List<MessageItem> deprecating = new List<MessageItem>();
        private float scrollDutation;

        private void DeprecateItem(MessageItem item)
        {
            if (appearing.Contains(item))
                appearing.Remove(item);
            item.SetActive(false);
            deprecating.Add(item);
        }

        private void AppearItem(MessageItem item, Vector2 appearPos, Action<UIListItem> onTweenEnd)
        {
            item.OnAppear(appearPos);
            item.onTweenEnd = onTweenEnd;
            appearing.Add(item);
        }

        protected override void Awake()
        {
            switch (arrangeMode)
            {
                case ArrangeMode.TopToBottom:
                    direction = Vector2.down;
                    cellDelta = itemSize.y;
                    break;
                case ArrangeMode.BottomToTop:
                    cellDelta = itemSize.y;
                    direction = Vector2.up;
                    break;
                case ArrangeMode.LeftToRight:
                    cellDelta = itemSize.x;
                    direction = Vector2.right;
                    break;
                case ArrangeMode.RightToLeft:
                    cellDelta = itemSize.x;
                    direction = Vector2.left;
                    break;

            }
            for (int i = 0; i <= maxCount + 2; ++i)
            {
                UIListItem item = GameObject.Instantiate<UIListItem>(prototype);
#if UNITY_EDITOR
                item.gameObject.name = "item" + (i + 1).ToString();
#endif
                item.gameObject.SetActive(true);
                item.transform.parent = transform;
                MessageItem mItem = new MessageItem(item, itemSize);
                DeprecateItem(mItem);
            }

            SetStay();
        }

        public override UIListItem AddListItem() => throw new NotImplementedException();

        public override bool interactable
        {
            set
            {
                new NotImplementedException();
            }
        }

        public bool CanAdd()
        {
            return state == State.STAY;
        }

        public void AddListItem(Action<int, UIListItem> listItemDrawer, Action<UIListItem> onEndFadeIn)
        {
            if (CanAdd())
            {
                var item = deprecating[0];
                bool b = deprecating.Remove(item);
                if (b)
                {
                    int rawCount = appearing.Count > maxCount ? maxCount : appearing.Count;
                    if (rawCount == 0)
                    {
                        AppearItem(item, Vector2.zero, onEndFadeIn);
                        appearing[appearing.Count - 1].PlayTween();
                        state = State.TWEEN;
                    }
                    else
                    {
                        Vector2 ap = (appearing.Count / 2f + .5f) * (cellDelta + space) * -direction;
                        AppearItem(item, ap, onEndFadeIn);
                        BeginScroll(appearing.Count > maxCount ? 1 : .5f);
                    }
                    listItemDrawer.Invoke(item.item.index, item.item);

                }
                else
                    Debug.LogError("Add item failed ! Deprecating items is EMPTY !!!");
            }
            else
                Debug.LogError("Try add item to UIMessageQueue when it's full");
        }

        private void BeginScroll(float coef)
        {
            appearing.ForEach(item => item.OnBeginScroll());
            state = State.SCROLL;
            scrollTarget = direction * coef * (cellDelta + space);
            elapsedTime = 0f;
            Vector2 vec = scrollTarget / scrollSpeed;
            scrollDutation = Mathf.Max(Mathf.Abs(vec.x), Mathf.Abs(vec.y));
        }

        private void SetStay()
        {
            state = State.STAY;
            stayTime = 0;
        }

        private void Update()
        {
            switch (state)
            {
                case State.TWEEN:
                    //播放完tween动画暂停滚动
                    if (!appearing[appearing.Count - 1].IsPlayingTween())
                    {
                        SetStay();
                    }
                    break;
                case State.SCROLL:
                //新的系统提示插入时，向上滚动
                    elapsedTime += Time.deltaTime;
                    Vector2 delta = Vector2.Lerp(Vector2.zero, scrollTarget, elapsedTime / scrollDutation);
                    appearing.ForEach(item => item.Scroll(delta));
                    if (elapsedTime > scrollDutation)
                    {
                        SetStay();
                        if (appearing[appearing.Count - 1].PlayTween())
                        {
                            state = State.TWEEN;
                        }

                        while (appearing.Count > maxCount)
                            DeprecateItem(appearing[0]);
                    }
                    break;
                case State.STAY:
                //STAY时间满足条件后逐条删除系统提示
                    if (appearing.Count > 0)
                    {
                        stayTime += Time.deltaTime;
                        if (stayTime > removeTime)
                        {
                            stayTime = 0;
                            DeprecateItem(appearing[0]);
                            if (appearing.Count > 0)
                            {
                                BeginScroll(.5f);
                            }

                        }
                    }

                    break;
            }
        }

        public override int count => appearing.Count;

        public override void DrawList(int count, LuaFunction listItemDrawer) => throw new NotImplementedException();

        public override void DrawItem(int index, LuaFunction listItemDrawer) => throw new NotImplementedException();


        public override void AddListItem(LuaFunction listItemDrawer) => throw new NotImplementedException();

        public override void ApplyForItems(LuaFunction drawer) => throw new NotImplementedException();

        public override void RemoveListItemByIndex(int index = -1) => throw new NotImplementedException();

        public override void Clear()
        {
            while (appearing.Count > 0)
                DeprecateItem(appearing[0]);
        }

        public override void SetListItemCount(int itemCount) => throw new NotImplementedException();
    }

}
