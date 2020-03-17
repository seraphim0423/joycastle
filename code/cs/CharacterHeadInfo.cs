using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using UnityEngine;
using GameWorld.Character;

public class CharacterHeadInfo : MonoBehaviour
{
    private float height;
    private bool isActive;
    private SortedDictionary<int, Component> components = new SortedDictionary<int, Component>();
    private Dictionary<int, RectTransform> moduleTrans = new Dictionary<int, RectTransform>();//做缓存防止每一帧都去GetComponent
    private bool isCaptain;
    private static Component leader = null;
    private float sizeSmallest = 0.4f;
    private static Transform m_tCamera;
    private Vector3 m_posCameraLast;
    private Vector3 m_posLast;
    private bool m_Refresh = false;
    private float spacing = 2f;
    [Range(0.1f,1f)]
    private static CameraParameters cparams;

    private static Transform tCamera
    {
        get
        {
            if (m_tCamera == null)
            {
                m_tCamera = Game.Client.Instance.MainCamera.transform;
            }
            return m_tCamera;
        }

    }

    private static CameraParameters CameraParams
    {
        get
        {
            if (cparams == null)
            {
                cparams = Game.Client.Instance.MainCamera.GetComponent<CameraParameters>();
            }
            return cparams;
        }
    }

    private void Start()
    {
        //m_tCamera = Game.Client.Instance.MainCamera.transform;
        m_posCameraLast = Vector3.zero;
        m_posLast = Vector3.zero;
        spacing = cfg.res.headinfooffset.infospacing / 100f;
    }

    public void SetHeight(float height)
    {
        this.height = height;
        m_Refresh = true;
    }

    public void Reset()
    {
        components.Clear();
        isCaptain = false;
        m_Refresh = true;
    }

    public void AttachModule(int idx, Component comp)
    {
        if (!components.ContainsKey(idx))
        {
            components.Add(idx, comp);
            comp.gameObject.SetActive(isActive);
            //Debug.Log("AttachModule: refresh");
            m_Refresh = true;
        }
    }

    public void DeattachModule(int idx)
    {
        if (components.ContainsKey(idx))
        {
            components.Remove(idx);
            if (moduleTrans.ContainsKey(idx))
            {
                moduleTrans.Remove(idx);
            }
            //Debug.Log("DeattachModule: refresh");
            m_Refresh = true;
        }
    }

    public bool IsActive
    {
        get { return isActive; }
        set
        {
            if (isActive ^ value)
            {
                isActive = value;
                components.Values.ForEach(comp =>
                {
                    comp.gameObject.SetActive(value);
                });
                m_Refresh = true;
            }
        }
    }

    public bool RegistedLeader()
    {
        return leader != null;
    }

    public void RegisteLeader(Component comp)
    {
        leader = comp;
        m_Refresh = true;
    }

    public void SetCaptain(bool b)
    {
        isCaptain = b;
        m_Refresh = true;
    }

    private void LateUpdate()
    {
        if (components.Count > 0)
        {
            //目标未移动、不更新
            if (Vector3.Distance(transform.position, m_posLast)> 0.01 ||
                Vector3.Distance(tCamera.position, m_posCameraLast) > 0.01 ||
                m_Refresh)
            {
                bool needWait = false;
                Vector3 targetPos = transform.position + Vector3.up * height;
                //Transform tCamera = Game.Client.Instance.MainCamera.transform;
                var n = tCamera.forward;
                var dir = targetPos - tCamera.position;
                float angle = Vector3.Angle(dir, n);
                if (angle > 90)
                {
                    //相机后方的头顶信息不显示、不更新
                    if (IsActive)
                        IsActive = false;
                }
                else
                {
                    if (!IsActive)
                        IsActive = true;


                    var dist = Vector3.Dot(dir, n) / n.magnitude;
                    var pos = targetPos;
                    var rot = tCamera.rotation;

                    //非完全的透视缩放， 远处相对放大一些，不然看不清楚
                    var zoom = (float)Math.Pow(dist / CameraParams.standardDistance, 2 - CameraParams.squareParam) * CameraParams.scaleParam;
                    var scale = Vector3.one * zoom;

                    var basePos = Vector2.zero;
                    //给头顶信息排序显示
                    foreach (KeyValuePair<int, Component> comp in components)
                    {
                        comp.Value.transform.position = targetPos;
                        comp.Value.transform.rotation = rot;
                        comp.Value.transform.localScale = scale;
                        if (comp.Key > cfg.ui.HeadInfoModules.STANDARD)
                        {
                            RectTransform rectTrans;
                            if (moduleTrans.ContainsKey(comp.Key))
                            {
                                rectTrans = moduleTrans[comp.Key];
                            }
                            else
                            {
                                rectTrans = comp.Value.transform.GetChild(0).gameObject.GetComponent<RectTransform>();
                            }
                            if(rectTrans.rect.height <= 0)
                            {
                                needWait = true;
                            }
                            else
                            {
                                rectTrans.anchoredPosition = basePos;
                                basePos += new Vector2(0f, rectTrans.rect.height + spacing);
                            }

                        }
                    }
                }

                m_posCameraLast = tCamera.position;
                m_posLast = transform.position;
                if (!needWait)
                {
                    m_Refresh = false;
                }
            }

        }
    }
    /*
    private Vector3 getStandardScale(Vector3 cameraPos, Vector3 targetPos)
    {
        var dist = (targetPos - cameraPos).magnitude;
        if(dist > smallDistance)
        {
            return Vector3.one * 0.8f;
        }
        else if(dist < standardDistance)
        {
            return Vector3.one;
        }
        else
        {
            return Vector3.one * (1 - (dist - standardDistance) / (smallDistance - standardDistance) * 0.2f);
        }
    }*/
}
