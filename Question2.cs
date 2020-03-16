using System;
using System.Collections.Generic;

namespace ConsoleApp2
{
    class SegmentTree
    {
        int from, to;
        int covers = 0;
        SegmentTree lChild = null;
        SegmentTree rChild = null;
        public SegmentTree(int n) : this(0, n - 1) { }

        public SegmentTree(int from, int to)
        {
            this.from = from;
            this.to = to;
        }

        public void Insert(int from, int to)
        {
            if(from <= this.from && to >= this.to)
            {
                ++covers;
            }
            else
            {
                int mid = (this.from + this.to) >> 1;
                if(from < mid)
                {
                    if(lChild == null)
                    {
                        lChild = new SegmentTree(this.from, mid);
                    }
                    lChild.Insert(from, to);
                }
                if(to > mid)
                {
                    if(rChild == null)
                    {
                        rChild = new SegmentTree(mid, this.to);
                    }
                    rChild.Insert(from, to);
                }
            }
        }

        public void Remove(int from, int to)
        {
            if(from <= this.from && to >= this.to)
            {
                --covers;
            }
            else
            {
                int mid = (this.from + this.to) >> 1;
                if(from < mid)
                {
                    lChild.Remove(from, to);
                }
                if(to > mid)
                {
                    rChild.Remove(from, to);
                }
            }
        }

        public bool Query(int from, int to)
        {
            if (covers > 0)
            {
                return true;
            }
            else
            {
                int mid = (this.from + this.to) >> 1;
                if(from < mid && lChild != null && lChild.Query(from, to))
                {
                    return true;
                }
                if(to > mid && rChild != null && rChild.Query(from, to))
                {
                    return true;
                }
                return false;
            }
        }
    }

    class Rectangle
    {
        public float x1, y1; // left bottom
        public float x2, y2; // top right
        public int index;
        public Rectangle(float[] values)
        {
            x1 = values[0];
            y1 = values[1];
            x2 = values[2];
            y2 = values[3];
        }

        public void SetIndex(int i)
        {
            this.index = i;
        }
    }

    class RectangleEnterComparer : IComparer<Rectangle>
    {
        public int Compare(Rectangle lhs, Rectangle rhs)
        {
            if(MathF.Abs(lhs.x1 - rhs.x1) > 1e-5f)
            {
                return lhs.x1 < rhs.x1 ? -1 : 1;
            }
            else
            {
                if(MathF.Abs(lhs.x2 - rhs.x2) > 1e-5)
                {
                    return lhs.x2 < rhs.x2 ? -1 : 1;
                }
                else
                {
                    return 0;
                }
            }
        }
    }

    class RectangleExitComparer : IComparer<Rectangle>
    {
        public int Compare(Rectangle lhs, Rectangle rhs)
        {
            if (MathF.Abs(lhs.x2 - rhs.x2) > 1e-5f)
            {
                return lhs.x2 < rhs.x2 ? -1 : 1;
            }
            else
            {
                if (MathF.Abs(lhs.x1 - rhs.x1) > 1e-5)
                {
                    return lhs.x1 < rhs.x1 ? -1 : 1;
                }
                else
                {
                    return 0;
                }
            }
        }
    }



    class Program
    {
        private static void DeWeight(List<float> values)
        {
            for(int i = values.Count - 1; i > 0; --i)
            {
                if(MathF.Abs(values[i] - values[i - 1]) < 1e-6f)
                {
                    values.Remove(i);
                }
            }
        }

        private static int BinarySearch(List<float> vlist, float v)
        {
            int s = 0, e = vlist.Count - 1;
            while(s <= e)
            {
                int mid = (s + e) >> 1;
                if(MathF.Abs(vlist[mid] - v) < 1e-6f)
                {
                    return mid;
                }
                else
                {
                    if(vlist[mid] > v)
                    {
                        e = mid - 1;
                    }
                    else
                    {
                        s = mid + 1;
                    }
                }
            }
            return -1; //err
        }
        private static void Solve(Rectangle[] rectangles)
        {
            for(int i = 0; i < rectangles.Length; ++i)
            {
                rectangles[i].SetIndex(i);
            }
            List<Rectangle> rectangleList = new List<Rectangle>(rectangles);
            rectangleList.Sort(new RectangleEnterComparer());
            List<int> enterOrder = new List<int>();
            foreach(var rect in rectangleList)
            {
                enterOrder.Add(rect.index);
            }
            rectangleList.Sort(new RectangleExitComparer());
            List<int> exitOrder = new List<int>();
            foreach(var rect in rectangleList)
            {
                exitOrder.Add(rect.index);
            }
            List<float> yList = new List<float>();
            foreach (var rect in rectangleList)
            {
                yList.Add(rect.y1);
                yList.Add(rect.y2);
            }
            DeWeight(yList);
            yList.Sort();

            int coveredRects = 0;
            int currEnter = 0;
            int currExit = 0;
            int currRects = 0;
            bool pad = false;
            var segTree = new SegmentTree(yList.Count);
            while(currEnter < rectangles.Length)
            {
                int enterIndex = enterOrder[currEnter];
                int exitIndex = exitOrder[currExit];
                var enterRect = rectangles[enterIndex];
                var exitRect = rectangles[exitIndex];
                if(MathF.Abs(exitRect.x2 - enterRect.x1) < 1e-6f || exitRect.x2 < enterRect.x1)
                {
                    int from = BinarySearch(yList, exitRect.y1);
                    int to = BinarySearch(yList, exitRect.y2);
                    segTree.Remove(from, to);
                    ++currExit;
                    --currRects;
                    pad = false;
                }
                else
                {
                    int from = BinarySearch(yList, enterRect.y1);
                    int to = BinarySearch(yList, enterRect.y2);
                    if (segTree.Query(from, to))
                    {
                        coveredRects += pad ? 2 : 1;
                    }
                    segTree.Insert(from, to);
                    ++currEnter;
                    if(++currRects == 1)
                    {
                        pad = true;
                    }
                }
            }
            Console.WriteLine(coveredRects);
        }

        static void Main(string[] args)
        {
            Rectangle rect1 = new Rectangle(new float[] { .5f, .5f, 1f, 1f });
            Rectangle rect2 = new Rectangle(new float[] { .75f, .75f, 1.5f, 1.5f });
            Rectangle rect3 = new Rectangle(new float[] { 1.25f, 0.5f, 2f, 2f });
            Rectangle rect4 = new Rectangle(new float[] { 1.25f, .2f, 2f, 0.4f });
            Solve(new Rectangle[] { rect3, rect1, rect4, rect2 });
            Console.ReadKey();
        }
    }
}
