using System;
using System.Collections.Generic;

namespace ConsoleApp1
{
    class Program
    {
        private static bool CanSplit(HashSet<string> words, string str)
        {
            bool[] d = new bool[str.Length + 1];
            d[0] = true;
            for (int i = 1; i <= str.Length; ++i)
            {
                for (int j = 0; j <= i; ++j)
                {
                    if (d[j] && (words.Contains(str.Substring(j, i - j))))
                    {
                        d[i] = true;
                        break;
                    }
                }
            }
            return d[str.Length];
        }

        private static void Solve(string[] words, string str)
        {
            HashSet<string> wordSet = new HashSet<string>(words);
            Console.WriteLine(CanSplit(wordSet, str));
        }

        static void Main(string[] args)
        {
            Solve(new string[] { "joy", "castle" }, "joycastle");
            Solve(new string[] { "joy", "castle", "cat" }, "castlejoycastlecatjoy");
            Solve(new string[] { "joy", "castle", "cat" }, "joycastlelovejoy");
            Console.ReadKey();
        }
    }
}
